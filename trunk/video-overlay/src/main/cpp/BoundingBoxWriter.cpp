/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2021 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2021 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

#include <jni.h>
#include <cstdlib>
#include <cmath>
#include <array>
#include <opencv2/opencv.hpp>
#include <opencv2/core.hpp>
#include <opencv2/videoio.hpp>
#include <opencv2/imgproc.hpp>

#include <MPFRotatedRect.h>
#include <frame_transformers/NoOpFrameTransformer.h>
#include <frame_transformers/IFrameTransformer.h>
#include <frame_transformers/AffineFrameTransformer.h>

#include "detectionComponentUtils.h"
#include "JniHelper.h"
#include "BoundingBoxImageHandle.h"
#include "BoundingBoxVideoHandle.h"


using namespace cv;
using namespace MPF;
using namespace COMPONENT;

// Provide enough room for long labels with wide characters to extend off the edges of the image.
constexpr int framePadding = 400;

template<typename TMediaHandle>
void markup(JNIEnv *env, jobject &boundingBoxWriterInstance, jobject mediaMetadata, jobject requestProperties,
            TMediaHandle &boundingBoxMediaHandle);

bool jniGetBoolProperty(JniHelper &jni, const std::string &key, jobject map, jmethodID methodId);

double jniGetDoubleProperty(JniHelper &jni, const std::string &key, double defaultValue, jobject map,
                            jmethodID methodId);

void drawBoundingBox(int x, int y, int width, int height, double boxRotation, bool boxFlip,
                     double mediaRotation, bool mediaFlip, int red, int green, int blue, double alpha,
                     bool animated, const std::string &label, bool labelChooseSide, Mat &image);

void drawLine(const Point2d &start, const Point2d &end, const Scalar &color, int lineThickness,
              bool animated, Mat &image);

void drawFrameNumber(int frameNumber, double alpha, Mat &image);

void drawBoundingBoxLabel(const Point2d &pt, double rotation, bool flip, const Scalar &color, double alpha,
                          int labelIndent, bool labelOnLeft, const std::string &label, Mat &image);


extern "C" {

JNIEXPORT void JNICALL Java_org_mitre_mpf_videooverlay_BoundingBoxWriter_markupVideoNative
  (JNIEnv *env, jobject boundingBoxWriterInstance, jstring sourceVideoPathJString, jobject mediaMetadata,
   jstring destinationVideoPathJString, jobject requestProperties)
{
    JniHelper jni(env);
    try {
        std::string sourceVideoPath = jni.ToStdString(sourceVideoPathJString);
        std::string destinationVideoPath = jni.ToStdString(destinationVideoPathJString);

        // Get the Map class and method.
        jclass clzMap = jni.FindClass("java/util/Map");
        jmethodID clzMap_fnGet = jni.GetMethodID(clzMap, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");

        auto jPropKey = jni.ToJString("MARKUP_VIDEO_VP9_CRF");
        jstring jPropValue = (jstring) jni.CallObjectMethod(requestProperties, clzMap_fnGet, *jPropKey);
        int crf = 31;
        if (jPropValue != nullptr) {
            std::string crfPropValue = jni.ToStdString(jPropValue);
            crf = std::stoi(crfPropValue);
        }

        int destinationVideoFramePadding = 0;
        if (jniGetBoolProperty(jni, "MARKUP_BORDER_ENABLED", requestProperties, clzMap_fnGet)) {
            destinationVideoFramePadding = framePadding;
        }

        BoundingBoxVideoHandle boundingBoxVideoHandle(sourceVideoPath, destinationVideoPath, crf,
                                                      destinationVideoFramePadding);

        markup(env, boundingBoxWriterInstance, mediaMetadata, requestProperties, boundingBoxVideoHandle);

        boundingBoxVideoHandle.Close();
    }
    catch (const std::exception &e) {
        jni.ReportCppException(e.what());
    }
    catch (...) {
        jni.ReportCppException();
    }
}

JNIEXPORT void JNICALL Java_org_mitre_mpf_videooverlay_BoundingBoxWriter_markupImageNative
  (JNIEnv *env, jobject boundingBoxWriterInstance, jstring sourceImagePathJString, jobject mediaMetadata,
   jstring destinationImagePathJString, jobject requestProperties)
{
    JniHelper jni(env);
    try {
        BoundingBoxImageHandle boundingBoxImageHandle(
                jni.ToStdString(sourceImagePathJString),
                jni.ToStdString(destinationImagePathJString));

        markup(env, boundingBoxWriterInstance, mediaMetadata, requestProperties, boundingBoxImageHandle);
    }
    catch (const std::exception &e) {
        jni.ReportCppException(e.what());
    }
    catch (...) {
        jni.ReportCppException();
    }
}

} // extern "C"


template<typename TMediaHandle>
void markup(JNIEnv *env, jobject &boundingBoxWriterInstance, jobject mediaMetadata, jobject requestProperties,
            TMediaHandle &boundingBoxMediaHandle)
{
    JniHelper jni(env);
    try {
        // Get the bounding box map.
        jclass clzBoundingBoxWriter = jni.GetObjectClass(boundingBoxWriterInstance);
        jmethodID clzBoundingBoxWriter_fnGetBoundingBoxMap =
            jni.GetMethodID(clzBoundingBoxWriter, "getBoundingBoxMap",
            "()Lorg/mitre/mpf/videooverlay/BoundingBoxMap;");
        jobject boundingBoxMap =
            jni.CallObjectMethod(boundingBoxWriterInstance, clzBoundingBoxWriter_fnGetBoundingBoxMap);

        // Get BoundingBoxMap methods.
        jclass clzBoundingBoxMap = jni.GetObjectClass(boundingBoxMap);
        jmethodID clzBoundingBoxMap_fnGet =
            jni.GetMethodID(clzBoundingBoxMap, "get", "(Ljava/lang/Object;)Ljava/lang/Object;"); // May be a list.
        jmethodID clzBoundingBoxMap_fnContainsKey =
            jni.GetMethodID(clzBoundingBoxMap, "containsKey", "(Ljava/lang/Object;)Z");

        // Get the Integer class and methods.
        jclass clzInteger = jni.FindClass("java/lang/Integer");
        jmethodID clzInteger_fnValueOf = jni.GetStaticMethodID(clzInteger, "valueOf", "(I)Ljava/lang/Integer;");

        // Get List class and methods.
        jclass clzList = jni.FindClass("java/util/List");
        jmethodID clzList_fnGet = jni.GetMethodID(clzList, "get", "(I)Ljava/lang/Object;");
        jmethodID clzList_fnSize = jni.GetMethodID(clzList, "size", "()I");

        // Get the Map class and methods.
        jclass clzMap = jni.FindClass("java/util/Map");
        jmethodID clzMap_fnGet = jni.GetMethodID(clzMap, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");

        // Get Optional class and methods.
        jclass clzOptional = jni.FindClass("java/util/Optional");
        jmethodID clzOptional_fnIsPresent = jni.GetMethodID(clzOptional, "isPresent", "()Z");
        jmethodID clzOptional_fnGet = jni.GetMethodID(clzOptional, "get", "()Ljava/lang/Object;");

        // Get BoundingBox class and methods.
        jclass clzBoundingBox = jni.FindClass("org/mitre/mpf/videooverlay/BoundingBox");
        jmethodID clzBoundingBox_fnGetX = jni.GetMethodID(clzBoundingBox, "getX", "()I");
        jmethodID clzBoundingBox_fnGetY = jni.GetMethodID(clzBoundingBox, "getY", "()I");
        jmethodID clzBoundingBox_fnGetHeight = jni.GetMethodID(clzBoundingBox, "getHeight", "()I");
        jmethodID clzBoundingBox_fnGetWidth = jni.GetMethodID(clzBoundingBox, "getWidth", "()I");
        jmethodID clzBoundingBox_fnGetRed = jni.GetMethodID(clzBoundingBox, "getRed", "()I");
        jmethodID clzBoundingBox_fnGetGreen = jni.GetMethodID(clzBoundingBox, "getGreen", "()I");
        jmethodID clzBoundingBox_fnGetBlue = jni.GetMethodID(clzBoundingBox, "getBlue", "()I");
        jmethodID clzBoundingBox_fnIsAnimated = jni.GetMethodID(clzBoundingBox, "isAnimated", "()Z");
        jmethodID clzBoundingBox_fnIsExemplar = jni.GetMethodID(clzBoundingBox, "isExemplar", "()Z");
        jmethodID clzBoundingBox_fnGetConfidence = jni.GetMethodID(clzBoundingBox, "getConfidence", "()F");
        jmethodID clzBoundingBox_fnGetLabel = jni.GetMethodID(clzBoundingBox, "getLabel", "()Ljava/util/Optional;");

        jmethodID clzBoundingBox_fnGetRotationDegrees = jni.GetMethodID(clzBoundingBox, "getRotationDegrees", "()D");
        jmethodID clzBoundingBox_fnGetFlip = jni.GetMethodID(clzBoundingBox, "getFlip", "()Z");

        // Get the media metadata rotation property.
        double mediaRotation = jniGetDoubleProperty(jni, "ROTATION", 0.0, mediaMetadata, clzMap_fnGet);

        // Get the media metadata horizontal flip property.
        bool mediaFlip = jniGetBoolProperty(jni, "HORIZONTAL_FLIP", mediaMetadata, clzMap_fnGet);

        // Get request properties.
        bool labelsEnabled =
            jniGetBoolProperty(jni, "MARKUP_LABELS_ENABLED", requestProperties, clzMap_fnGet);
        double labelsAlpha =
            jniGetDoubleProperty(jni, "MARKUP_LABELS_ALPHA", 1.0, requestProperties, clzMap_fnGet);
        bool labelsChooseSideEnabled =
            jniGetBoolProperty(jni, "MARKUP_LABELS_CHOOSE_SIDE_ENABLED", requestProperties, clzMap_fnGet);
        bool borderEnabled =
            jniGetBoolProperty(jni, "MARKUP_BORDER_ENABLED", requestProperties, clzMap_fnGet);
        bool exemplarsEnabled =
            jniGetBoolProperty(jni, "MARKUP_VIDEO_EXEMPLARS_ENABLED", requestProperties, clzMap_fnGet);
        bool frameNumbersEnabled =
            jniGetBoolProperty(jni, "MARKUP_VIDEO_FRAME_NUMBERS_ENABLED", requestProperties, clzMap_fnGet);

        Size origFrameSize = boundingBoxMediaHandle.GetFrameSize();
        Mat frame;

        jint currentFrameNum = -1;
        while (true) {
            currentFrameNum++;
            jobject currentFrameBoxed = jni.CallStaticObjectMethod(clzInteger, clzInteger_fnValueOf, currentFrameNum);

            if (!boundingBoxMediaHandle.Read(frame) || frame.empty()) {
                break;
            }

            // Add a black border to allow boxes and labels to extend off the edges of the image.  
            cv::copyMakeBorder(frame, frame, framePadding, framePadding, framePadding, framePadding,
                               cv::BORDER_CONSTANT, Scalar(0, 0, 0));

            jboolean foundEntryForCurrentFrame = jni.CallBooleanMethod(boundingBoxMap,
                                                                       clzBoundingBoxMap_fnContainsKey,
                                                                       currentFrameBoxed);

            if (foundEntryForCurrentFrame) {
                jobject currentFrameElements =
                    jni.CallObjectMethod(boundingBoxMap, clzBoundingBoxMap_fnGet, currentFrameBoxed);

                // Iterate through this list, drawing each box on the frame.
                jint numBoxesCurrentFrame = jni.CallIntMethod(currentFrameElements, clzList_fnSize);

                for (jint i = 0; i < numBoxesCurrentFrame; i++) {
                    jobject box = jni.CallObjectMethod(currentFrameElements, clzList_fnGet, i);
                    jint x = jni.CallIntMethod(box, clzBoundingBox_fnGetX);
                    jint y = jni.CallIntMethod(box, clzBoundingBox_fnGetY);

                    jint height = jni.CallIntMethod(box, clzBoundingBox_fnGetHeight);
                    if (height == 0) {
                        height = origFrameSize.height;
                    }

                    jint width = jni.CallIntMethod(box, clzBoundingBox_fnGetWidth);
                    if (width == 0) {
                        width = origFrameSize.width;
                    }

                    jint red = jni.CallIntMethod(box, clzBoundingBox_fnGetRed);
                    jint green = jni.CallIntMethod(box, clzBoundingBox_fnGetGreen);
                    jint blue = jni.CallIntMethod(box, clzBoundingBox_fnGetBlue);
                    jboolean animated = jni.CallBooleanMethod(box, clzBoundingBox_fnIsAnimated);
                    jfloat confidence = jni.CallFloatMethod(box, clzBoundingBox_fnGetConfidence);

                    double boxRotation = (double)jni.CallDoubleMethod(box, clzBoundingBox_fnGetRotationDegrees);
                    bool boxFlip = (bool)jni.CallBooleanMethod(box, clzBoundingBox_fnGetFlip);

                    std::stringstream ss;

                    if (labelsEnabled) {
                        jobject labelObj = jni.CallObjectMethod(box, clzBoundingBox_fnGetLabel);
                        if (jni.CallBooleanMethod(labelObj, clzOptional_fnIsPresent)) {
                            std::string label =
                                jni.ToStdString((jstring)jni.CallObjectMethod(labelObj, clzOptional_fnGet));
                            ss << label.substr(0, 10); // truncate long strings
                            if (label.length() > 10) {
                                ss << "...";
                            }
                            ss << ' ';
                        }

                        ss << std::fixed << std::setprecision(3) << confidence;

                        if (exemplarsEnabled && boundingBoxMediaHandle.markExemplar &&
                                jni.CallBooleanMethod(box, clzBoundingBox_fnIsExemplar)) {
                            ss << '!';
                        }
                    }

                    drawBoundingBox(x + framePadding, y + framePadding, width, height, boxRotation, boxFlip,
                                    mediaRotation, mediaFlip, red, green, blue, labelsAlpha, animated, ss.str(),
                                    labelsChooseSideEnabled, frame);
                }
            }

            // Crop the padding off if we're not keeping the border.
            if (!borderEnabled) {
                frame = frame(cv::Rect(framePadding, framePadding, origFrameSize.width, origFrameSize.height));
            }

            // Generate the final frame by flipping and/or rotating the raw frame to account for media metadata.
            AffineFrameTransformer frameTransformer(
                    mediaRotation, mediaFlip, Scalar(0, 0, 0),
                    IFrameTransformer::Ptr(new NoOpFrameTransformer(frame.size())));

            frameTransformer.TransformFrame(frame, 0);

            if (frameNumbersEnabled && boundingBoxMediaHandle.showFrameNumbers) {
                drawFrameNumber(currentFrameNum, labelsAlpha, frame);
            }

            boundingBoxMediaHandle.HandleMarkedFrame(frame);
        }

    }
    catch (const std::exception &e) {
        jni.ReportCppException(e.what());
    }
    catch (...) {
        jni.ReportCppException();
    }
}


bool jniGetBoolProperty(JniHelper &jni, const std::string &key, jobject map, jmethodID methodId) {
    auto jPropKey = jni.ToJString(key);
    auto jPropValue = (jstring) jni.CallObjectMethod(map, methodId, *jPropKey);
    return jPropValue != nullptr && jni.ToBool(jPropValue);
}

double jniGetDoubleProperty(JniHelper &jni, const std::string &key, double defaultValue, jobject map,
                            jmethodID methodId) {
    auto jPropKey = jni.ToJString(key);
    auto jPropValue = (jstring) jni.CallObjectMethod(map, methodId, *jPropKey);
    double retval = defaultValue;
    if (jPropValue != nullptr) {
        std::string propValue = jni.ToStdString(jPropValue);
        retval = std::stod(propValue);
    }
    return retval;
}


void drawBoundingBox(int x, int y, int width, int height, double boxRotation, bool boxFlip,
                     double mediaRotation, bool mediaFlip, int red, int green, int blue, double alpha, bool animated,
                     const std::string &label, bool labelChooseSide, Mat &image)
{
    // Calculate the box coordinates relative to the raw frame.
    // The frame is "raw" in the sense that it's not flipped and/or rotated to account for media metadata.
    std::array<Point2d, 4> corners = MPFRotatedRect(x, y, width, height, boxRotation, boxFlip).GetCorners();
    auto detectionTopLeftPt = corners[0];

    Scalar boxColor(blue, green, red);
    int minDim = width < height ? width : height;

    // Because we use LINE_AA below for anti-aliasing, which uses a Gaussian filter, the lack of pixels near the edge
    // of the frame causes a problem when attempting to draw a line along the edge using a thickness of 1.
    // Specifically, no pixels will be drawn near the edge.
    // Refer to: https://stackoverflow.com/questions/42484955/pixels-at-arrow-tip-missing-when-using-antialiasing
    // To address this, we use a minimum thickness of 2.
    int lineThickness = (int) std::max(.0018 * (image.rows < image.cols ? image.cols : image.rows), 2.0);

    int minCircleRadius = 3;
    int circleRadius = lineThickness == 1 ? minCircleRadius : lineThickness + 5;

    double maxCircleCoverage = minDim * 0.25; // circle should not cover more than 25% of the minimum dimension
    if (circleRadius > maxCircleCoverage) {
        circleRadius = std::max((int)maxCircleCoverage, minCircleRadius);
    }

    if (!label.empty()) {
        // Calculate the adjusted box coordinates relative to the final frame.
        // The frame is "final" in the sense that it's flipped and/or rotated to account for media metadata.
        MPFRotatedRect adjRotatedRect(x, y, width, height,
                                      boxFlip ? boxRotation + mediaRotation : boxRotation - mediaRotation,
                                      boxFlip ^ mediaFlip);
        std::array<Point2d, 4> adjCorners = adjRotatedRect.GetCorners();

        // Get the top point of box in final frame. The lower-left corner of the black label rectangle will later be
        // positioned here (see drawBoundingBoxLabel()), ensuring that the label will never appear within the detection box.
        auto adjTopPtIter = std::min_element(adjCorners.begin(), adjCorners.end(), [](Point const& a, Point const& b) {
            return std::tie(a.y, a.x) < std::tie(b.y, b.x); // left takes precedence over right
        });
        int adjTopPtIndex = std::distance(adjCorners.begin(), adjTopPtIter);
        Point2d adjTopPt = adjCorners[adjTopPtIndex];

        // Get point of box in raw frame that corresponds to the top point in the box in the final frame.
        Point2d rawTopPt = corners[adjTopPtIndex];

        bool labelOnLeft = false;
        if (labelChooseSide) {
            // Determine if the label should be on the left or right side of the top point.
            // Our goal is to prevent the label from extending past the leftmost or rightmost point, if possible.
            Rect2d adjRect = adjRotatedRect.GetBoundingRect();
            Point2d adjRectCenter = (adjRect.br() + adjRect.tl()) * 0.5;
            labelOnLeft = (adjTopPt.x > adjRectCenter.x);
        }

        int labelIndent = circleRadius + 2;
        drawBoundingBoxLabel(rawTopPt, mediaRotation, mediaFlip, boxColor, alpha, labelIndent, labelOnLeft,
                             label, image);
    }

    drawLine(corners[0], corners[1], boxColor, lineThickness, animated, image);
    drawLine(corners[1], corners[2], boxColor, lineThickness, animated, image);
    drawLine(corners[2], corners[3], boxColor, lineThickness, animated, image);
    drawLine(corners[3], corners[0], boxColor, lineThickness, animated, image);

    circle(image, detectionTopLeftPt, circleRadius, boxColor, cv::LineTypes::FILLED, cv::LineTypes::LINE_AA);
}

void drawLine(const Point2d &start, const Point2d &end, const Scalar &color, int lineThickness,
              bool animated, Mat &image)
{
    if (!animated) {
        line(image, start, end, color, lineThickness, cv::LineTypes::LINE_AA);
        return;
    }

    // Draw dashed line.
    double lineLen = pow(pow(start.x - end.x, 2) + pow(start.y - end.y, 2), .5);

    int dashLen = 10 + lineThickness;
    double maxDashCoverage = lineLen * 0.5; // dash segment should not occupy more than 50% of the total line length
    if (dashLen > maxDashCoverage) {
        dashLen = (int)maxDashCoverage;
    }

    double step = dashLen / lineLen;
    Point prev = start;
    double percent = 0.0;
    bool draw = true;

    do {
        percent = std::min(percent + step, 1.0);
        int x = (start.x * (1 - percent) + end.x * percent) + 0.5;
        int y = (start.y * (1 - percent) + end.y * percent) + 0.5;
        Point curr(x, y);
        if (draw) {
            line(image, prev, curr, color, lineThickness);
        }
        prev = curr;
        draw = !draw;
    } while (percent < 1.0);
}

void drawFrameNumber(int frameNumber, double alpha, Mat &image)
{
    std::string label = std::to_string(frameNumber);

    int labelPadding = 8;
    double labelScale = 0.8;
    int labelThickness = 2;
    int labelFont = cv::FONT_HERSHEY_SIMPLEX;

    int baseline = 0;
    Size labelSize = getTextSize(label, labelFont, labelScale, labelThickness, &baseline);

    int labelRectWidth = labelSize.width + (2 * labelPadding);
    int labelRectHeight = labelSize.height + (2 * labelPadding);

    // Position frame number near top right of the frame.
    int labelRectTopLeftX = image.cols - 10 - labelRectWidth;
    int labelRectTopLeftY = 10;

    // Create the black rectangle in which to put the label text.
    Mat labelMat = Mat::zeros(labelRectHeight, labelRectWidth, image.type());

    int labelBottomLeftX = labelPadding;
    int labelBottomLeftY = labelSize.height + labelPadding;

    cv::putText(labelMat, label, Point(labelBottomLeftX, labelBottomLeftY), labelFont, labelScale,
                Scalar(255, 255, 255), labelThickness, cv::LineTypes::LINE_AA);

    // Place the label on the image.
    cv::Rect labelMatInsertRect(labelRectTopLeftX, labelRectTopLeftY, labelMat.cols, labelMat.rows);
    auto insertionRegion = image(labelMatInsertRect);
    cv::addWeighted(insertionRegion, 1 - alpha, labelMat, alpha, 0, insertionRegion);
}

void drawBoundingBoxLabel(const Point2d &pt, double rotation, bool flip, const Scalar &color, double alpha,
                          int labelIndent, bool labelOnLeft, const std::string &label, Mat &image)
{
    int labelPadding = 8;
    double labelScale = 0.8;
    int labelThickness = 2;
    int labelFont = cv::FONT_HERSHEY_SIMPLEX;

    int baseline = 0;
    Size labelSize = getTextSize(label, labelFont, labelScale, labelThickness, &baseline);

    int labelRectBottomLeftX = pt.x;
    int labelRectBottomLeftY = pt.y;
    int labelRectTopRightX = pt.x + labelIndent + labelSize.width + labelPadding;
    int labelRectTopRightY = pt.y - labelSize.height - (2 * labelPadding);

    int labelRectWidth = labelRectTopRightX - labelRectBottomLeftX;
    int labelRectHeight = labelRectBottomLeftY - labelRectTopRightY;

    // Create the black rectangle in which to put the label text.
    Mat labelMat = Mat::zeros(labelRectHeight, labelRectWidth, image.type());

    int labelBottomLeftX = labelIndent;
    int labelBottomLeftY = labelSize.height + labelPadding;

    cv::putText(labelMat, label, Point(labelBottomLeftX, labelBottomLeftY),
                labelFont, labelScale, color, labelThickness, cv::LineTypes::LINE_AA);

    if (flip) {
        cv::flip(labelMat, labelMat, 1); // flip around y-axis so the text appears left-to-right in the final frame
    }

    // Next we will place the black label rectangle (labelMat) with text in a white square (paddedLabelMat).
    // The lower-left corner of the rectangle is positioned in the center of the square, shown with an X below:
    //
    //    +--------------------+
    //    |                    |
    //    |        +---------+ |
    //    |        |         | |
    //    |        X---------+ |
    //    |                    |
    //    |                    |
    //    |                    |
    //    +--------------------+

    // Calculate the diagonal distance from the lower-left corner of the rectangle to the upper-right corner.
    // This distance is half the length of a side of the square, enough for the rectangle to be rotated a full 360
    // degrees within the square.
    int labelRectMaxDim = ceil(sqrt(pow(labelRectWidth, 2) + pow(labelRectHeight, 2)));

    Mat paddedLabelMat(labelRectMaxDim * 2, labelRectMaxDim * 2, image.type(), Scalar(255,255,255));

    // Place the label rectangle within the square on the right side of the point, as shown in the above diagram,
    // unless labelOnLeft is true, in which case place it on the left side of the point.
    // If the label is flipped, move the rectangle to the other side of the point to account for how it will be flipped
    // again when generating the final frame.
    cv::Rect labelMatInsertRect(flip ^ labelOnLeft ? labelRectMaxDim - labelRectWidth : labelRectMaxDim,
                                labelRectMaxDim - labelRectHeight, labelMat.cols, labelMat.rows);
    labelMat.copyTo(paddedLabelMat(labelMatInsertRect));

    // Rotate the white box such that the label rectangle with text will be orientated horizontally in the final frame.
    bool hasRotation = !DetectionComponentUtils::RotationAnglesEqual(rotation, 0);
    if (hasRotation) {
        Point2d center(labelRectMaxDim, labelRectMaxDim);
        Mat r = cv::getRotationMatrix2D(center, rotation, 1.0);
        cv::warpAffine(paddedLabelMat, paddedLabelMat, r, paddedLabelMat.size(),
                       cv::InterpolationFlags::INTER_CUBIC, cv::BORDER_CONSTANT, cv::Scalar(255, 255, 255));
    }

    try {
        // Place the white box on the image. Align the center of the box (which corresponds to the lower-left corner of
        // the label rectangle) with the desired location (pt).
        cv::Rect paddedLabelMatInsertRect(labelRectBottomLeftX - labelRectMaxDim,
                                          labelRectBottomLeftY - labelRectMaxDim,
                                          paddedLabelMat.cols, paddedLabelMat.rows);
        auto insertionRegion = image(paddedLabelMatInsertRect);
        insertionRegion.forEach<cv::Vec3b>([&](cv::Vec3b &pixel, const int position[]) {
            if (paddedLabelMat.at<cv::Vec3b>(position) != cv::Vec3b{255, 255, 255}) {
                pixel = (1 - alpha) * pixel + alpha * paddedLabelMat.at<cv::Vec3b>(position);
            }
        });
    } catch (std::exception& e) {
        // Depending on the position of the detection relative to the frame boundary, sometimes the label cannot be
        // drawn within the viewable region. This is fine. Log and continue.
        std::cerr << "Warning: Label outside of viewable region." << std::endl;
    }
}

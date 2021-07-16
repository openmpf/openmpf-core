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

package org.mitre.mpf.wfm.camel.operations.detection.padding;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Doubles;
import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.Task;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.camel.WfmProcessor;
import org.mitre.mpf.wfm.camel.operations.detection.trackmerging.TrackMergingContext;
import org.mitre.mpf.wfm.data.DetectionErrorUtil;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.enums.IssueCodes;
import org.mitre.mpf.wfm.enums.MediaType;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

@Component(DetectionPaddingProcessor.REF)
public class DetectionPaddingProcessor extends WfmProcessor {
    public static final String REF = "detectionPaddingProcessor";

    private static final Logger _log = LoggerFactory.getLogger(DetectionPaddingProcessor.class);

    private final JsonUtils _jsonUtils;

    private final InProgressBatchJobsService _inProgressBatchJobs;

    private final AggregateJobPropertiesUtil _aggregateJobPropertiesUtil;

    @Inject
    public DetectionPaddingProcessor(
            JsonUtils jsonUtils,
            InProgressBatchJobsService inProgressBatchJobs,
            AggregateJobPropertiesUtil aggregateJobPropertiesUtil) {
        _jsonUtils = jsonUtils;
        _inProgressBatchJobs = inProgressBatchJobs;
        _aggregateJobPropertiesUtil = aggregateJobPropertiesUtil;
    }


    @Override
    public void wfmProcess(Exchange exchange) throws WfmProcessingException {
        TrackMergingContext trackMergingContext = _jsonUtils.deserialize(exchange.getIn().getBody(byte[].class),
                                                                         TrackMergingContext.class);
        BatchJob job = _inProgressBatchJobs.getJob(trackMergingContext.getJobId());
        Task task = job.getPipelineElements().getTask(trackMergingContext.getTaskIndex());

        for (int actionIndex = 0; actionIndex < task.getActions().size(); actionIndex++) {
            Action action = job.getPipelineElements().getAction(trackMergingContext.getTaskIndex(), actionIndex);

            for (Media media : job.getMedia()) {
                if (media.isFailed()
                        || (media.getType() != MediaType.IMAGE && media.getType() != MediaType.VIDEO)) {
                    continue;
                }

                Function<String, String> combinedProperties =
                        _aggregateJobPropertiesUtil.getCombinedProperties(job, media, action);

                Collection<Track> tracks = _inProgressBatchJobs.getTracks(job.getId(), media.getId(),
                        trackMergingContext.getTaskIndex(), actionIndex);

                if (Iterables.size(tracks) > 0) {

                    int frameWidth = Integer.parseInt(media.getMetadata().get("FRAME_WIDTH"));
                    int frameHeight = Integer.parseInt(media.getMetadata().get("FRAME_HEIGHT"));

                    Iterable<Track> filteredTracks = removeIllFormedDetections(job.getId(), media.getId(),
                            trackMergingContext.getTaskIndex(), actionIndex,
                            frameWidth, frameHeight, tracks);

                    try {
                        if (requiresPadding(combinedProperties)) {

                            String xPadding = combinedProperties.apply(MpfConstants.DETECTION_PADDING_X);
                            String yPadding = combinedProperties.apply(MpfConstants.DETECTION_PADDING_Y);

                            processTracks(job.getId(), media.getId(), trackMergingContext.getTaskIndex(), actionIndex,
                                    xPadding, yPadding, frameWidth, frameHeight, filteredTracks);
                        }
                    } catch (DetectionPaddingException e) {
                        // This should not happen because we checked that the detection properties were valid when the
                        // job was created.
                        throw new WfmProcessingException(e);
                    }
                }
            }
        }

        exchange.getOut().setBody(exchange.getIn().getBody());
    }


    /**
     * Ensures that the detection padding properties are valid..
     * @param properties Properties to validate
     * @throws DetectionPaddingException when invalid detection padding properties are provided.
     */
    public static void validatePaddingProperties(Function<String, String> properties)
            throws DetectionPaddingException {
        // Both will throw if property is invalid.
        requiresPadding(properties, MpfConstants.DETECTION_PADDING_X);
        requiresPadding(properties, MpfConstants.DETECTION_PADDING_Y);
    }


    private static boolean requiresPadding(Function<String, String> properties)
            throws DetectionPaddingException {
        return requiresPadding(properties, MpfConstants.DETECTION_PADDING_X) ||
               requiresPadding(properties, MpfConstants.DETECTION_PADDING_Y);
    }


    private static boolean requiresPadding(Function<String, String> properties, String propertyName)
            throws DetectionPaddingException {
        String padding = properties.apply(propertyName);
        if (StringUtils.isBlank(padding)) {
            return false;
        }
        try {
            if (padding.endsWith("%")) {
                double percent = Double.parseDouble(padding.substring(0, padding.length() - 1));
                if (percent <= -50.0) {
                    // can't shrink to nothing
                    throw new DetectionPaddingException(String.format(
                            "The %s property was set to \"%s\", but that would result in empty detections. " +
                                    "When specified as a percentage, padding values must be > -50%%.",
                            propertyName, padding));
                }
                return percent != 0.0;
            }
            return Integer.parseInt(padding) != 0;
        } catch (NumberFormatException e) {
            throw new DetectionPaddingException(String.format(
                    "The %s property was set to \"%s\", but that is not a valid value. " +
                            "Padding must be specified as whole number integer, or a percentage that ends " +
                            "with \"%%\". Percentages can be decimal values. Negative values are allowed in both " +
                            "cases, but percentages must be > -50%%.",
                    propertyName, padding), e);
        }
    }

    public Collection<Track> removeIllFormedDetections(long jobId, long mediaId, int taskIndex, int actionIndex,
                                                       int frameWidth, int frameHeight,
                                                       Iterable<Track> tracks) {
        // Remove any detections with zero width/height, or that are entirely outside of the frame.
        // If the number of detections goes to 0, drop the track.
        // Do not remove ill-formed detections for those types that are exempted, because they normally do not generate
        // bounding boxes for detections.
        if (_aggregateJobPropertiesUtil.isExemptFromIllFormedDetectionRemoval(tracks.iterator().next().getType())) {
            return (Collection<Track>) tracks;
        }

        var newTracks = new TreeSet<Track>();
        var zeroSizeFrames = IntStream.builder();
        var outsideFrames = IntStream.builder();
        var frameBoundingBox = new Rectangle2D.Double(0, 0, frameWidth, frameHeight);
        for (Track track : tracks) {
            SortedSet<Detection> goodDetections = new TreeSet<>();
            for (Detection detection : track.getDetections()) {
                var detectionBoundingBox = new Rectangle2D.Double(detection.getX(), detection.getY(),
                        detection.getWidth(), detection.getHeight());
                if (detection.getWidth() == 0 || detection.getHeight() == 0) {
                    zeroSizeFrames.add(detection.getMediaOffsetFrame());
                }
                else if (frameBoundingBox.createIntersection(detectionBoundingBox).isEmpty()) {
                    outsideFrames.add(detection.getMediaOffsetFrame());
                }
                else {
                    goodDetections.add(detection);
                }
            }
            if (goodDetections.size() > 0) {
                newTracks.add(new Track(
                        track.getJobId(),
                        track.getMediaId(),
                        track.getTaskIndex(),
                        track.getActionIndex(),
                        goodDetections.first().getMediaOffsetFrame(),
                        goodDetections.last().getMediaOffsetFrame(),
                        goodDetections.first().getMediaOffsetTime(),
                        goodDetections.last().getMediaOffsetTime(),
                        track.getType(),
                        track.getConfidence(),
                        goodDetections,
                        track.getTrackProperties()));
            }
            else {
                _log.warn("Empty track dropped after removing ill-formed detection(s)");
            }

        }

        Optional<String> zeroSizeFramesString = zeroSizeFrames.build()
                .boxed()
                .collect(DetectionErrorUtil.toFrameRangesString());

        Optional<String> outsideFramesString = outsideFrames.build()
                .boxed()
                .collect(DetectionErrorUtil.toFrameRangesString());

        if (zeroSizeFramesString.isPresent()) {
            _log.warn(String.format("Dropped one or more detection regions for job id %s with zero width and height. " +
                            "frames dropped: %s",
                    jobId, zeroSizeFramesString.get()));
            _inProgressBatchJobs.addWarning(
                    jobId, mediaId, IssueCodes.PADDING, String.format(
                            "Dropped one or more ill-formed detection regions with width or height equal to 0 {%s}",
                            zeroSizeFramesString.get()));
        }

        if (outsideFramesString.isPresent()) {
            _log.warn(String.format("Dropped one or more detection regions for job id %s with bounding box completely outside of the frame. " +
                            "frames dropped: %s",
                    jobId, outsideFramesString.get()));
            _inProgressBatchJobs.addWarning(
                    jobId, mediaId, IssueCodes.PADDING, String.format(
                            "Dropped one or more ill-formed detection regions with bounding box completely outside frame {%s}",
                            outsideFramesString.get()));
        }
        if (zeroSizeFramesString.isPresent() || outsideFramesString.isPresent()) {
            _inProgressBatchJobs.setTracks(jobId, mediaId, taskIndex, actionIndex, newTracks);
        }

        return newTracks;
    }

    private void processTracks(long jobId, long mediaId, int taskIndex, int actionIndex, String xPadding, String yPadding,
                               int frameWidth, int frameHeight, Iterable<Track> tracks) {
        var newTracks = new TreeSet<Track>();
        var shrunkToNothingFrames = IntStream.builder();

        for (Track track : tracks) {
            SortedSet<Detection> newDetections = new TreeSet<>();

            for (Detection detection : track.getDetections()) {
                Detection newDetection = padDetection(xPadding, yPadding, frameWidth, frameHeight, detection);
                if (newDetection.getDetectionProperties().containsKey("SHRUNK_TO_NOTHING")) {
                    shrunkToNothingFrames.add(newDetection.getMediaOffsetFrame());
                }
                newDetections.add(newDetection);
            }

            newTracks.add(new Track(
                    track.getJobId(),
                    track.getMediaId(),
                    track.getTaskIndex(),
                    track.getActionIndex(),
                    track.getStartOffsetFrameInclusive(),
                    track.getEndOffsetFrameInclusive(),
                    track.getStartOffsetTimeInclusive(),
                    track.getEndOffsetTimeInclusive(),
                    track.getType(),
                    track.getConfidence(),
                    newDetections,
                    track.getTrackProperties()));
        }

        Optional<String> shrunkToNothingString = shrunkToNothingFrames.build()
                .boxed()
                .collect(DetectionErrorUtil.toFrameRangesString());

        if (shrunkToNothingString.isPresent()) {
            _log.warn(String.format("Shrunk one or more detection regions for job id %s to nothing. " +
                                            "1-pixel detection regions used instead. %s",
                                    jobId, shrunkToNothingString.get()));

            _inProgressBatchJobs.addWarning(
                    jobId, mediaId, IssueCodes.PADDING, String.format(
                    "Shrunk one or more detection regions to nothing. " +
                            "1-pixel detection regions used instead. %s", shrunkToNothingString.get()));
            _inProgressBatchJobs.setTracks(jobId, mediaId, taskIndex, actionIndex, newTracks);
        }
    }


    /**
     * Padding is applied uniformly on both sides of the detection region.
     * For example, an x padding value of 50 increases the width by 100 px (50 px padded on the left and right,
     * respectively).
     * For example, an x padding value of 50% on a region with a width of 100 px results in a width of 200 px
     * (50% of 100 px is 50 px, which is padded on the left and right, respectively).
     * If the detection width or height is shrunk to nothing, use a 1-pixel width or height, respectively.
     */
    public static Detection padDetection(String xPadding, String yPadding, int frameWidth, int frameHeight,
                                         Detection detection) {
        double rotationDegrees = Optional.ofNullable(detection.getDetectionProperties().get("ROTATION"))
                .filter(StringUtils::isNotBlank)
                .map(Double::parseDouble)
                .orElse(0.0);

        AffineTransform transform = AffineTransform.getRotateInstance(Math.toRadians(rotationDegrees));

        Rectangle2D.Double rotationCorrectedDetectionRect = transformDetection(detection, transform);
        Rectangle2D.Double grownDetectionRect = grow(rotationCorrectedDetectionRect, xPadding, yPadding);
        Rectangle2D.Double clippedDetectionRect = clip(grownDetectionRect, frameWidth, frameHeight, transform);
        Rectangle2D.Double detectionRectMappedBack = inverseTransform(clippedDetectionRect, transform);

        return rectToDetection(detectionRectMappedBack, detection);
    }


    private static Rectangle2D.Double transformDetection(Detection detection, AffineTransform transform) {
        double[] newTopLeft = new double[2];
        transform.transform(new double[] { detection.getX(), detection.getY() }, 0, newTopLeft, 0, 1);
        return new Rectangle2D.Double(newTopLeft[0], newTopLeft[1],
                                      detection.getWidth(), detection.getHeight());
    }


    private static Rectangle2D.Double grow(Rectangle2D.Double rect, String xPadding, String yPadding) {
        double changeInWidth = getPaddingPixelCount(xPadding, rect.getWidth());
        double changeInHeight = getPaddingPixelCount(yPadding, rect.getHeight());

        double newX = rect.getX() - changeInWidth;
        double newY = rect.getY() - changeInHeight;
        double newWidth = rect.getWidth() + 2 * changeInWidth;
        double newHeight = rect.getHeight() + 2 * changeInHeight;
        return new Rectangle2D.Double(newX, newY, newWidth, newHeight);
    }


    private static double getPaddingPixelCount(String padding, double totalLength) {
        if (padding.endsWith("%")) {
            double percent = Double.parseDouble(padding.substring(0, padding.length() - 1));
            percent = Math.max(-50, percent); // can't shrink to less than nothing
            return totalLength * percent / 100.0;
        }
        int offset = Integer.parseInt(padding);
        if (totalLength + (offset * 2) < 0) { // can't shrink to less than nothing
            return totalLength / -2.0;
        }
        return offset;
    }


    private static Rectangle2D.Double clip(Rectangle2D.Double detectionRect, int frameWidth, int frameHeight,
                                           AffineTransform transform) {
        Rectangle2D.Double frameRect = getTransformedFrameRect(frameWidth, frameHeight, transform);
        Rectangle2D.Double result = new Rectangle2D.Double();
        Rectangle2D.intersect(detectionRect, frameRect, result);
        return result;
    }


    private static Rectangle2D.Double getTransformedFrameRect(int frameWidth, int frameHeight,
                                                              AffineTransform transform) {
        double[] preTransformCorners = {
                0, 0,
                0, frameHeight - 1,
                frameWidth - 1, 0,
                frameWidth - 1, frameHeight - 1 };

        double[] corners = new double[preTransformCorners.length];

        transform.transform(preTransformCorners, 0, corners, 0, preTransformCorners.length / 2);

        double[] transformedXs = { corners[0], corners[2], corners[4], corners[6] };
        double minX = Doubles.min(transformedXs);
        double maxX = Doubles.max(transformedXs);

        double[] transformedYs = { corners[1], corners[3], corners[5], corners[7] };
        double minY = Doubles.min(transformedYs);
        double maxY = Doubles.max(transformedYs);

        return new Rectangle2D.Double(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }


    private static Rectangle2D.Double inverseTransform(Rectangle2D.Double rect, AffineTransform transform) {
        try {
            double[] newTopLeft = new double[2];
            transform.inverseTransform(new double[] { rect.getX(), rect.getY() }, 0, newTopLeft, 0, 1);
            return new Rectangle2D.Double(newTopLeft[0], newTopLeft[1], rect.getWidth(), rect.getHeight());
        }
        catch (NoninvertibleTransformException e) {
            // It is impossible for this exception to be thrown because rotation is always invertible.
            throw new IllegalStateException(e);
        }
    }


    private static Detection rectToDetection(Rectangle2D.Double rect, Detection originalDetection) {
        int x = (int) rect.getX();
        int y = (int) rect.getY();
        int width = (int) Math.ceil(rect.getWidth());
        int height = (int) Math.ceil(rect.getHeight());
        boolean shrunkToNothing = false;
        if (width <= 0) {
            width = 1;
            shrunkToNothing = true;
        }
        if (height <= 0) {
            height = 1;
            shrunkToNothing = true;
        }

        Map<String, String> detectionProperties;
        if (shrunkToNothing) {
            detectionProperties = ImmutableMap.<String, String>builder()
                    .putAll(originalDetection.getDetectionProperties())
                    .put("SHRUNK_TO_NOTHING", "TRUE")
                    .build();
        }
        else {
            detectionProperties = originalDetection.getDetectionProperties();
        }

        return new Detection(
                x, y, width, height,
                originalDetection.getConfidence(),
                originalDetection.getMediaOffsetFrame(),
                originalDetection.getMediaOffsetTime(),
                detectionProperties);
    }
}

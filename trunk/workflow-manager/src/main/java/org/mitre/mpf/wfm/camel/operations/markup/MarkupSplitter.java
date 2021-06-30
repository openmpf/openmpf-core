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

package org.mitre.mpf.wfm.camel.operations.markup;

import org.apache.camel.Message;
import org.apache.camel.impl.DefaultMessage;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.mime.MimeTypes;
import org.javasimon.aop.Monitored;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.ActionType;
import org.mitre.mpf.rest.api.pipelines.Algorithm;
import org.mitre.mpf.rest.api.pipelines.Task;
import org.mitre.mpf.videooverlay.BoundingBox;
import org.mitre.mpf.videooverlay.BoundingBoxMap;
import org.mitre.mpf.videooverlay.BoundingBoxSource;
import org.mitre.mpf.wfm.buffers.Markup;
import org.mitre.mpf.wfm.data.IdGenerator;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.JobPipelineElements;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.enums.MediaType;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.mitre.mpf.wfm.enums.MpfEndpoints;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.util.MarkupJobPropertiesUtil;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.stream.DoubleStream;

@Component
@Monitored
public class MarkupSplitter {
    private static final Logger log = LoggerFactory.getLogger(MarkupSplitter.class);

    @Autowired
    private InProgressBatchJobsService inProgressJobs;

    @Autowired
    private PropertiesUtil propertiesUtil;

    @Autowired
    private MarkupResultDao hibernateMarkupResultDao;

    @Autowired
    private MarkupJobPropertiesUtil markupJobPropertiesUtil;


    public List<Message> performSplit(BatchJob job, Task task) {
        List<Message> messages = new ArrayList<>();

        int lastDetectionTaskIndex = findLastDetectionTaskIndex(job.getPipelineElements());

        hibernateMarkupResultDao.deleteByJobId(job.getId());

        for (int actionIndex = 0; actionIndex < task.getActions().size(); actionIndex++) {
            String actionName = task.getActions().get(actionIndex);
            Action action = job.getPipelineElements().getAction(actionName);
            int mediaIndex = -1;
            for (Media media : job.getMedia()) {
                mediaIndex++;
                if (media.isFailed()) {
                    log.debug("Skipping '{}: {}' - it is in an error state.", media.getId(), media.getLocalPath());
                } else if(media.getType() != MediaType.IMAGE && media.getType() != MediaType.VIDEO) {
                    log.debug("Skipping Media {} - only image and video files are eligible for markup.", media.getId());
                } else {
                    List<Markup.BoundingBoxMapEntry> boundingBoxMapEntryList
                            = createMap(job, media, lastDetectionTaskIndex,
                                        job.getPipelineElements().getTask(lastDetectionTaskIndex))
                            .toBoundingBoxMapEntryList();

                    Path destinationPath;
                    if (boundingBoxMapEntryList.isEmpty()) {
                        destinationPath = propertiesUtil.createMarkupPath(job.getId(), media.getId(),
                                getFileExtension(media.getMimeType()));
                    } else {
                        destinationPath = propertiesUtil.createMarkupPath(job.getId(), media.getId(),
                                getMarkedUpMediaExtensionForMediaType(job, media));
                    }

                    Markup.MarkupRequest.Builder requestBuilder = Markup.MarkupRequest.newBuilder()
                            .setMediaIndex(mediaIndex)
                            .setTaskIndex(job.getCurrentTaskIndex())
                            .setActionIndex(actionIndex)
                            .setMediaId(media.getId())
                            .setMediaType(Markup.MediaType.valueOf(media.getType().toString().toUpperCase()))
                            .setRequestId(IdGenerator.next())
                            .setSourceUri(media.getProcessingPath().toUri().toString())
                            .setDestinationUri(destinationPath.toUri().toString())
                            .addAllMapEntries(boundingBoxMapEntryList);

                    for (var entry : media.getMetadata().entrySet()) {
                        requestBuilder.addMediaMetadataBuilder()
                                .setKey(entry.getKey())
                                .setValue(entry.getValue());
                    }

                    for (var entry : markupJobPropertiesUtil.getPropertyMap(job, media, action).entrySet()) {
                        requestBuilder.addMarkupPropertiesBuilder()
                                .setKey(entry.getKey())
                                .setValue(entry.getValue());
                    }

                    Algorithm algorithm = job.getPipelineElements().getAlgorithm(action.getAlgorithm());
                    DefaultMessage message = new DefaultMessage(); // We will sort out the headers later.
                    message.setHeader(MpfHeaders.RECIPIENT_QUEUE, String.format("jms:MPF.%s_%s_REQUEST", algorithm.getActionType(), action.getAlgorithm()));
                    message.setHeader(MpfHeaders.JMS_REPLY_TO, StringUtils.replace(MpfEndpoints.COMPLETED_MARKUP, "jms:", ""));
                    message.setBody(requestBuilder.build());
                    messages.add(message);
                }
            }
        }

        return messages;
    }


    /**
     * Returns the last task in the pipeline containing a detection action. This effectively filters preprocessor
     * detections so that the output is not cluttered with motion detections.
     */
    private static int findLastDetectionTaskIndex(JobPipelineElements pipeline) {
        int taskIndex = -1;
        for (int i = 0; i < pipeline.getTaskCount(); i++) {
            ActionType actionType = pipeline.getAlgorithm(i, 0).getActionType();
            if(actionType == ActionType.DETECTION) {
                taskIndex = i;
            }
        }
        return taskIndex;
    }

    /** Creates a BoundingBoxMap containing all of the tracks which were produced by the specified action history keys. */
    private BoundingBoxMap createMap(BatchJob job, Media media, int taskIndex, Task task) {
        boolean labelFromDetections = Boolean.parseBoolean(
                markupJobPropertiesUtil.getValue(MpfConstants.MARKUP_LABELS_FROM_DETECTIONS, job, media));
        String labelTextPropToShow =
                markupJobPropertiesUtil.getValue(MpfConstants.MARKUP_LABELS_TEXT_PROP_TO_SHOW, job, media);
        String labelNumericPropToShow =
                markupJobPropertiesUtil.getValue(MpfConstants.MARKUP_LABELS_NUMERIC_PROP_TO_SHOW, job, media);
        Iterator<Color> trackColors = getTrackColors();
        BoundingBoxMap boundingBoxMap = new BoundingBoxMap();
        long mediaId = media.getId();
        for (int actionIndex = 0; actionIndex < task.getActions().size(); actionIndex++) {
            SortedSet<Track> tracks = inProgressJobs.getTracks(job.getId(), mediaId, taskIndex, actionIndex);
            for (Track track : tracks) {
                addTrackToBoundingBoxMap(track, boundingBoxMap, trackColors.next(), labelFromDetections,
                        labelTextPropToShow, labelNumericPropToShow);
            }
        }
        return boundingBoxMap;
    }


    private static OptionalDouble getRotation(Map<String, String> properties) {
        String rotationString = properties.get("ROTATION");
        if (rotationString == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Double.parseDouble(rotationString));
    }


    private static Optional<Boolean> getFlip(Map<String, String> properties) {
        return Optional.ofNullable(properties.get("HORIZONTAL_FLIP"))
                .map(s -> Boolean.parseBoolean(s.strip()));
    }

    private static void addTrackToBoundingBoxMap(Track track, BoundingBoxMap boundingBoxMap, Color trackColor,
                                                 boolean labelFromDetections, String labelTextPropToShow,
                                                 String labelNumericPropToShow) {
        OptionalDouble trackRotation = getRotation(track.getTrackProperties());
        Optional<Boolean> trackFlip = getFlip(track.getTrackProperties());

        Optional<String> label = Optional.empty();
        boolean moving = false;
        if (!labelFromDetections) { // get track-level details
            label = getLabel(track, labelTextPropToShow, labelNumericPropToShow);
            moving = Boolean.parseBoolean(track.getTrackProperties().get("MOVING"));
        }

        List<Detection> orderedDetections = new ArrayList<>(track.getDetections());
        Collections.sort(orderedDetections);
        for (int i = 0; i < orderedDetections.size(); i++) {
            Detection detection = orderedDetections.get(i);
            int currentFrame = detection.getMediaOffsetFrame();

            BoundingBoxSource detectionSource = BoundingBoxSource.DETECTION_ALGORITHM;
            if (Boolean.parseBoolean(detection.getDetectionProperties().get("FILLED_GAP"))) {
                detectionSource = BoundingBoxSource.TRACKING_FILLED_GAP;
            }

            OptionalDouble detectionRotation = getRotation(detection.getDetectionProperties());
            Optional<Boolean> detectionFlip = getFlip(detection.getDetectionProperties());

            if (labelFromDetections) { // get detection-level details
                label = getLabel(detection, labelTextPropToShow, labelNumericPropToShow);
                moving = Boolean.parseBoolean(detection.getDetectionProperties().get("MOVING"));
            }

            // Create a bounding box at the location.
            BoundingBox boundingBox = new BoundingBox(
                    detection.getX(),
                    detection.getY(),
                    detection.getWidth(),
                    detection.getHeight(),
                    detectionRotation.orElse(trackRotation.orElse(0)),
                    detectionFlip.orElse(trackFlip.orElse(false)),
                    trackColor.getRed(),
                    trackColor.getGreen(),
                    trackColor.getBlue(),
                    detectionSource,
                    moving,
                    track.getExemplar().equals(detection),
                    label);

            String objectType = track.getType();
            if ("SPEECH".equalsIgnoreCase(objectType) || "AUDIO".equalsIgnoreCase(objectType)) {
                // Special case: Speech doesn't populate object locations for each frame in the video, so you have to
                // go by the track start and stop frames.
                boundingBoxMap.putOnFrames(track.getStartOffsetFrameInclusive(),
                                           track.getEndOffsetFrameInclusive(), boundingBox);
                break;
            }

            boolean isLastDetection = (i == (orderedDetections.size() - 1));
            if (isLastDetection) {
                boundingBoxMap.putOnFrame(currentFrame, boundingBox);
                break;
            }

            Detection nextDetection = orderedDetections.get(i + 1);
            int gapBetweenNextDetection = nextDetection.getMediaOffsetFrame() - detection.getMediaOffsetFrame();
            if (gapBetweenNextDetection == 1) {
                boundingBoxMap.putOnFrame(currentFrame, boundingBox);
            }
            else {
                // Since the gap between frames is greater than 1 and we are not at the last result in the
                // collection, we draw bounding boxes on each frame in the collection such that on the
                // first frame, the bounding box is at the position given by the object location, and on the
                // last frame in the interval, the bounding box is very close to the position given by the object
                // location of the next result. Consequently, the original bounding box appears to resize
                // and translate to the position and size of the next result's bounding box.

                OptionalDouble nextDetectionRotation = getRotation(nextDetection.getDetectionProperties());
                Optional<Boolean> nextDetectionFlip = getFlip(nextDetection.getDetectionProperties());

                if (labelFromDetections) { // get detection-level details
                    label = getLabel(nextDetection, labelTextPropToShow, labelNumericPropToShow);
                }

                BoundingBox nextBoundingBox = new BoundingBox(
                        nextDetection.getX(),
                        nextDetection.getY(),
                        nextDetection.getWidth(),
                        nextDetection.getHeight(),
                        nextDetectionRotation.orElse(trackRotation.orElse(0)),
                        nextDetectionFlip.orElse(trackFlip.orElse(false)),
                        boundingBox.getRed(),
                        boundingBox.getBlue(),
                        boundingBox.getGreen(),
                        BoundingBoxSource.ANIMATION,
                        moving,
                        false, // not exemplar
                        label);
                boundingBoxMap.animate(boundingBox, nextBoundingBox, currentFrame, gapBetweenNextDetection);
            }
        }
    }



    /** Returns the appropriate markup extension for a given {@link MediaType}. */
    private String getMarkedUpMediaExtensionForMediaType(BatchJob job, Media media) {
        switch (media.getType()) {
            case IMAGE:
                return ".png";
            case VIDEO:
                String encoder = markupJobPropertiesUtil.getValue(MpfConstants.MARKUP_VIDEO_ENCODER, job, media)
                        .toLowerCase();
                switch (encoder) {
                    case ("vp9"):
                        return ".webm";
                    case ("h264"):
                        return ".mp4";
                    case ("mjpeg"):
                        return ".avi";
                    default:
                        log.warn("\"" + encoder + "\" is not a valid encoder. Defaulting to mjpeg.");
                        return ".avi";
                }
            case AUDIO: // Falls through
            case UNKNOWN: // Falls through
            default:
                return ".bin";
        }
    }

    private static String getFileExtension(String mimeType) {
        try {
            return MimeTypes.getDefaultMimeTypes().forName(mimeType).getExtension();
        } catch (Exception exception) {
            log.warn("Failed to map the MIME type '{}' to an extension. Defaulting to .bin.", mimeType);
            return ".bin";
        }
    }

    private static final double GOLDEN_RATIO_CONJUGATE = 2 / (1 + Math.sqrt(5));

    // Uses method described in https://martin.ankerl.com/2009/12/09/how-to-create-random-colors-programmatically/
    // to create an infinite iterator of randomish colors.
    // The article says to use the HSV color space, but HSB is identical.
    private static Iterator<Color> getTrackColors() {
        return DoubleStream.iterate(0.5, x -> (x + GOLDEN_RATIO_CONJUGATE) % 1)
                .mapToObj(x -> Color.getHSBColor((float) x, 0.5f, 0.95f))
                .iterator();
    }

    private static Optional<String> getLabel(String textPart, String numericPart) {
        String label = "";
        if (textPart != null) {
            label += String.format("%.10s", textPart).strip();
        }
        if (numericPart != null) {
            try {
                float numericVal = Float.parseFloat(numericPart);
                label += String.format(" %.3f", numericVal);
            } catch (NumberFormatException e) {
                log.warn("Failed to convert '{}' to a float when generating bounding box label.", numericPart);
            }
        }
        label = label.strip();
        return label.isBlank() ? Optional.empty() : Optional.of(label);
    }

    public static Optional<String> getLabel(Track track, String textProp, String numericProp) {
        String textStr = track.getTrackProperties().get(textProp);
        if (textStr == null) {
            textStr = track.getExemplar().getDetectionProperties().get(textProp);
        }
        String numericStr = track.getTrackProperties().get(numericProp);
        if (numericStr == null) {
            numericStr = track.getExemplar().getDetectionProperties().get(numericProp);
        }
        if (numericStr == null && numericProp.equalsIgnoreCase("CONFIDENCE")) {
            numericStr = Float.toString(track.getConfidence());
        }
        return getLabel(textStr, numericStr);
    }

    public static Optional<String> getLabel(Detection detection, String textProp, String numericProp) {
        String textStr = detection.getDetectionProperties().get(textProp);
        String numericStr = detection.getDetectionProperties().get(numericProp);
        if (numericStr == null && numericProp.equalsIgnoreCase("CONFIDENCE")) {
            numericStr = Float.toString(detection.getConfidence());
        }
        return getLabel(textStr, numericStr);
    }
}

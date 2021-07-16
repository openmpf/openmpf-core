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

package org.mitre.mpf.wfm.camel.operations.detection;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf;
import org.mitre.mpf.wfm.camel.ResponseProcessor;
import org.mitre.mpf.wfm.camel.operations.detection.trackmerging.TrackMergingContext;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.DetectionProcessingError;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.FrameTimeInfo;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.mitre.mpf.wfm.util.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/** Processes the responses which have been returned from a detection component. */
@Component(DetectionResponseProcessor.REF)
public class DetectionResponseProcessor
        extends ResponseProcessor<DetectionProtobuf.DetectionResponse> {
    public static final String REF = "detectionResponseProcessor";
    private static final Logger log = LoggerFactory.getLogger(DetectionResponseProcessor.class);

    @Autowired
    private AggregateJobPropertiesUtil aggregateJobPropertiesUtil;

    private final JsonUtils jsonUtils = new JsonUtils(ObjectMapperFactory.customObjectMapper());

    @Autowired
    private InProgressBatchJobsService inProgressJobs;

    public DetectionResponseProcessor() {
        super(DetectionProtobuf.DetectionResponse.class);
    }

    @Override
    public Object processResponse(long jobId, DetectionProtobuf.DetectionResponse detectionResponse, Map<String, Object> headers) throws WfmProcessingException {
        int totalResponses = detectionResponse.getVideoResponsesCount() +
                detectionResponse.getAudioResponsesCount() +
                detectionResponse.getImageResponsesCount() +
                detectionResponse.getGenericResponsesCount();

        if (totalResponses > 1) {
            throw new WfmProcessingException(
                    // Camel will print out the exchange, including the message body content, in the stack trace.
                    String.format("Unsupported operation. More than one DetectionResponse sub-message found for job %d.", jobId));
        }

        if (totalResponses != 0) {
            BatchJob job = inProgressJobs.getJob(jobId);
            Media media = job.getMedia()
                    .stream()
                    .filter(m -> m.getId() == detectionResponse.getMediaId())
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException("Unable to locate media with id: " +
                            detectionResponse.getMediaId()));
            Action action = job.getPipelineElements().getAction(detectionResponse.getActionName());
            double confidenceThreshold = calculateConfidenceThreshold(action, job, media);

            if (detectionResponse.getVideoResponsesCount() != 0) {
                processVideoResponse(jobId, detectionResponse, detectionResponse.getVideoResponses(0), confidenceThreshold, media);
            } else if (detectionResponse.getAudioResponsesCount() != 0) {
                processAudioResponse(jobId, detectionResponse, detectionResponse.getAudioResponses(0), confidenceThreshold);
            } else if (detectionResponse.getImageResponsesCount() != 0) {
                processImageResponse(jobId, detectionResponse, detectionResponse.getImageResponses(0), confidenceThreshold);
            } else {
                processGenericResponse(jobId, detectionResponse, detectionResponse.getGenericResponses(0), confidenceThreshold);
            }
        }
        else {
            String mediaLabel = getBasicMediaLabel(detectionResponse);
            log.warn("[{}] Response received, but no tracks were found for {}.", getLogLabel(jobId, detectionResponse), mediaLabel);
            checkErrors(jobId, mediaLabel, detectionResponse, 0, 0, 0, 0);
        }

        return jsonUtils.serialize(new TrackMergingContext(jobId, detectionResponse.getTaskIndex()));
    }

    private double calculateConfidenceThreshold(Action action, BatchJob job, Media media) {
        String confidenceThresholdProperty = aggregateJobPropertiesUtil.getValue(
                MpfConstants.CONFIDENCE_THRESHOLD_PROPERTY, job, media, action);

        try {
            return Double.parseDouble(confidenceThresholdProperty);
        }
        catch (NumberFormatException e) {
            log.warn("Invalid confidence threshold specified: value should be numeric. Provided value was: "
                             + confidenceThresholdProperty);
            return job.getSystemPropertiesSnapshot().getConfidenceThreshold();
        }
    }

    private void processVideoResponse(long jobId, DetectionProtobuf.DetectionResponse detectionResponse,
                                      DetectionProtobuf.DetectionResponse.VideoResponse videoResponse,
                                      double confidenceThreshold, Media media) {
        int startFrame = videoResponse.getStartFrame();
        int stopFrame = videoResponse.getStopFrame();
        var frameTimeInfo = media.getFrameTimeInfo();
        int startTime = frameTimeInfo.getFrameTimeMs(startFrame);
        int stopTime = frameTimeInfo.getFrameTimeMs(stopFrame);

        String mediaLabel = String.format("Media #%d, Frames: %d-%d, Task: '%s', Action: '%s'",
                detectionResponse.getMediaId(),
                startFrame,
                stopFrame,
                detectionResponse.getTaskName(),
                detectionResponse.getActionName());

        log.debug("[{}] Response received for {}.", getLogLabel(jobId, detectionResponse), mediaLabel);
        checkErrors(jobId, mediaLabel, detectionResponse, startFrame, stopFrame, startTime, stopTime);

        // Begin iterating through the tracks that were found by the detector.
        for (DetectionProtobuf.VideoTrack objectTrack : videoResponse.getVideoTracksList()) {
            if (objectTrack.getConfidence() < confidenceThreshold) {
                continue;
            }

            int startOffsetTime = frameTimeInfo.getFrameTimeMs(objectTrack.getStartFrame());
            int stopOffsetTime  = frameTimeInfo.getFrameTimeMs(objectTrack.getStopFrame());

            ImmutableSortedSet<Detection> detections = objectTrack.getFrameLocationsList()
                    .stream()
                    .filter(flm -> flm.getImageLocation().getConfidence() >= confidenceThreshold)
                    .map(flm -> toDetection(flm, frameTimeInfo))
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));

            if (!detections.isEmpty()) {
                Track track = new Track(
                        jobId,
                        detectionResponse.getMediaId(),
                        detectionResponse.getTaskIndex(),
                        detectionResponse.getActionIndex(),
                        objectTrack.getStartFrame(),
                        objectTrack.getStopFrame(),
                        startOffsetTime,
                        stopOffsetTime,
                        videoResponse.getDetectionType(),
                        objectTrack.getConfidence(),
                        detections,
                        toMap(objectTrack.getDetectionPropertiesList()));

                inProgressJobs.addTrack(track);
            }
        }
    }

    private void processAudioResponse(long jobId, DetectionProtobuf.DetectionResponse detectionResponse,
                                      DetectionProtobuf.DetectionResponse.AudioResponse audioResponse,
                                      double confidenceThreshold) {

        int startTime = audioResponse.getStartTime();
        int stopTime = audioResponse.getStopTime();
        String mediaLabel = String.format("Media #%d, Time: %d-%d, Task: '%s', Action: '%s'",
                detectionResponse.getMediaId(),
                startTime,
                stopTime,
                detectionResponse.getTaskName(),
                detectionResponse.getActionName());

        log.debug("[{}] Response received for {}.", getLogLabel(jobId, detectionResponse), mediaLabel);
        checkErrors(jobId, mediaLabel, detectionResponse, 0, 0, startTime, stopTime);

        // Begin iterating through the tracks that were found by the detector.
        for (DetectionProtobuf.AudioTrack objectTrack : audioResponse.getAudioTracksList()) {
            if (objectTrack.getConfidence() >= confidenceThreshold) {
                SortedMap<String, String> properties = toMap(objectTrack.getDetectionPropertiesList());
                Detection detection = new Detection(
                        0,
                        0,
                        0,
                        0,
                        objectTrack.getConfidence(),
                        0,
                        objectTrack.getStartTime(),
                        properties);

                Track track = new Track(
                        jobId,
                        detectionResponse.getMediaId(),
                        detectionResponse.getTaskIndex(),
                        detectionResponse.getActionIndex(),
                        0,
                        0,
                        objectTrack.getStartTime(),
                        objectTrack.getStopTime(),
                        audioResponse.getDetectionType(),
                        objectTrack.getConfidence(),
                        ImmutableSortedSet.of(detection),
                        properties);

                inProgressJobs.addTrack(track);
            }
        }
    }

    private void processImageResponse(long jobId, DetectionProtobuf.DetectionResponse detectionResponse,
                                      DetectionProtobuf.DetectionResponse.ImageResponse imageResponse,
                                      double confidenceThreshold) {
        String mediaLabel = getBasicMediaLabel(detectionResponse);
        log.debug("[{}] Response received for {}.", getLogLabel(jobId, detectionResponse), mediaLabel);

        checkErrors(jobId, mediaLabel, detectionResponse, 0, 1, 0, 0);

        // Iterate through the list of detections. It is assumed that detections are not sorted in a meaningful way.
        for (DetectionProtobuf.ImageLocation location : imageResponse.getImageLocationsList()) {
            if (location.getConfidence() >= confidenceThreshold) {
                Track track = new Track(
                        jobId,
                        detectionResponse.getMediaId(),
                        detectionResponse.getTaskIndex(),
                        detectionResponse.getActionIndex(),
                        0,
                        1,
                        0,
                        0,
                        imageResponse.getDetectionType(),
                        location.getConfidence(),
                        ImmutableSortedSet.of(toDetection(location, 0, 0)),
                        toMap(location.getDetectionPropertiesList()));
                inProgressJobs.addTrack(track);
            }
        }
    }

    private void processGenericResponse(long jobId, DetectionProtobuf.DetectionResponse detectionResponse,
                                        DetectionProtobuf.DetectionResponse.GenericResponse genericResponse,
                                        double confidenceThreshold) {
        String mediaLabel = getBasicMediaLabel(detectionResponse);
        log.debug("[{}] Response received for {}.", getLogLabel(jobId, detectionResponse), mediaLabel);

        checkErrors(jobId, mediaLabel, detectionResponse, 0, 0, 0, 0);

        // Begin iterating through the tracks that were found by the detector.
        for (DetectionProtobuf.GenericTrack objectTrack : genericResponse.getGenericTracksList()) {
            if (objectTrack.getConfidence() >= confidenceThreshold) {
                SortedMap<String, String> properties = toMap(objectTrack.getDetectionPropertiesList());

                Detection detection = new Detection(
                        0,
                        0,
                        0,
                        0,
                        objectTrack.getConfidence(),
                        0,
                        0,
                        properties);

                Track track = new Track(
                        jobId,
                        detectionResponse.getMediaId(),
                        detectionResponse.getTaskIndex(),
                        detectionResponse.getActionIndex(),
                        0,
                        0,
                        0,
                        0,
                        genericResponse.getDetectionType(),
                        objectTrack.getConfidence(),
                        ImmutableSortedSet.of(detection),
                        properties);

                inProgressJobs.addTrack(track);
            }
        }
    }

    private void checkErrors(long jobId, String mediaLabel, DetectionProtobuf.DetectionResponse detectionResponse,
                             int startFrame, int stopFrame, int startTime, int stopTime) {

        if (detectionResponse.getError() != DetectionProtobuf.DetectionError.NO_DETECTION_ERROR) {
            String errorCode;
            String errorMessage;
            // Some error occurred during detection. Store this error.
            if (detectionResponse.getError() == DetectionProtobuf.DetectionError.REQUEST_CANCELLED) {
                log.warn("[{}] Job cancelled while processing {}.", getLogLabel(jobId, detectionResponse), mediaLabel);
                errorCode = MpfConstants.REQUEST_CANCELLED;
                errorMessage = "Successfully cancelled.";
            }
            else {
                log.error("[{}] Encountered a detection error while processing {}: {}",
                        getLogLabel(jobId, detectionResponse), mediaLabel, detectionResponse.getError());
                errorCode = Objects.toString(detectionResponse.getError());
                errorMessage = detectionResponse.getErrorMessage();
            }
            inProgressJobs.addDetectionProcessingError(new DetectionProcessingError(
                    jobId,
                    detectionResponse.getMediaId(),
                    detectionResponse.getTaskIndex(),
                    detectionResponse.getActionIndex(),
                    startFrame,
                    stopFrame,
                    startTime,
                    stopTime,
                    errorCode,
                    errorMessage));
        }
    }

    private static Detection toDetection(
            DetectionProtobuf.VideoTrack.FrameLocationMap frameLocationMap,
            FrameTimeInfo timeInfo) {
        int time = timeInfo.getFrameTimeMs(frameLocationMap.getFrame());
        return toDetection(frameLocationMap.getImageLocation(), frameLocationMap.getFrame(), time);
    }

    private static Detection toDetection(DetectionProtobuf.ImageLocation location, int frameNumber, int time) {
        SortedMap<String, String> detectionProperties = toMap(location.getDetectionPropertiesList());
        return new Detection(
                location.getXLeftUpper(),
                location.getYLeftUpper(),
                location.getWidth(),
                location.getHeight(),
                location.getConfidence(),
                frameNumber,
                time,
                detectionProperties);
    }

    private static SortedMap<String, String> toMap(Collection<DetectionProtobuf.PropertyMap> properties) {
        return properties.stream()
                .collect(ImmutableSortedMap.toImmutableSortedMap(
                        Comparator.naturalOrder(),
                        DetectionProtobuf.PropertyMap::getKey,
                        DetectionProtobuf.PropertyMap::getValue));
    }

    private static String getLogLabel(long jobId, DetectionProtobuf.DetectionResponse detectionResponse) {
        return String.format("Job %d|%d|%d", jobId, detectionResponse.getTaskIndex(), detectionResponse.getActionIndex());
    }

    private static String getBasicMediaLabel(DetectionProtobuf.DetectionResponse detectionResponse) {
        return String.format("Media #%d, Task: '%s', Action: '%s'",
                detectionResponse.getMediaId(), detectionResponse.getTaskName(), detectionResponse.getActionName());
    }
}

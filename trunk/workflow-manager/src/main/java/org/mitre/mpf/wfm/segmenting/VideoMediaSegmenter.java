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

package org.mitre.mpf.wfm.segmenting;

import org.apache.camel.Message;
import org.apache.camel.impl.DefaultMessage;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf.DetectionRequest.VideoRequest;
import org.mitre.mpf.wfm.camel.operations.detection.DetectionContext;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.util.TimePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

import org.mitre.mpf.wfm.enums.MpfHeaders;

@Component(VideoMediaSegmenter.REF)
public class VideoMediaSegmenter implements MediaSegmenter {
    private static final Logger log = LoggerFactory.getLogger(VideoMediaSegmenter.class);
    public static final String REF = "videoMediaSegmenter";


    @Override
    public List<Message> createDetectionRequestMessages(
            Media media, DetectionContext context) {
        if (context.isFirstDetectionTask()) {
            return createTimePairMessages(
                    media, context, Collections.singletonList(new TimePair(0, media.getLength() - 1)));
        }
        else if (MediaSegmenter.feedForwardIsEnabled(context)) {
            return createFeedForwardMessages(media, context);
        }
        else {
            List<TimePair> trackTimePairs = MediaSegmenter.createTimePairsForTracks(context.getPreviousTracks());
            return createTimePairMessages(media, context, trackTimePairs);
        }
    }


    private static List<Message> createTimePairMessages(
            Media media, DetectionContext context, Collection<TimePair> trackTimePairs) {

        List<TimePair> segments = MediaSegmenter.createSegments(
                trackTimePairs,
                context.getSegmentingPlan().getTargetSegmentLength(),
                context.getSegmentingPlan().getMinSegmentLength(),
                context.getSegmentingPlan().getMinGapBetweenSegments());

        List<Message> messages = new ArrayList<>(segments.size());
        for(TimePair segment : segments) {
            assert segment.getStartInclusive() >= 0
                    : String.format("Segment start must always be GTE 0. Actual: %d", segment.getStartInclusive());
            assert segment.getEndInclusive() >= 0
                    : String.format("Segment end must always be GTE 0. Actual: %d", segment.getEndInclusive());
            log.debug("Creating segment [{}, {}] for {}.",
                      segment.getStartInclusive(), segment.getEndInclusive(), media.getId());

            VideoRequest videoRequest = VideoRequest.newBuilder()
                    .setStartFrame(segment.getStartInclusive())
                    .setStopFrame(segment.getEndInclusive())
                    .build();
            Message message = createProtobufMessage(media, context, videoRequest);
            message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
            messages.add(message);
        }
        return messages;
    }


    private static Message createProtobufMessage(
            Media media,
            DetectionContext context,
            VideoRequest videoRequest) {

        DetectionProtobuf.DetectionRequest detectionRequest = MediaSegmenter.initializeRequest(media, context)
                .setDataType(DetectionProtobuf.DetectionRequest.DataType.VIDEO)
                .setVideoRequest(videoRequest)
                .build();

        Message message = new DefaultMessage();
        message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
        message.setBody(detectionRequest);
        return message;
    }


    private static List<Message> createFeedForwardMessages(Media media, DetectionContext context) {
        int topConfidenceCount = getTopConfidenceCount(context);

        List<Message> messages = new ArrayList<>();
        for (Track track : context.getPreviousTracks()) {
            if (track.getDetections().isEmpty()) {
                log.warn("Found track with no detections. No feed forward request will be created for: {}", track);
                continue;
            }

            VideoRequest videoRequest = createFeedForwardVideoRequest(track, topConfidenceCount);
            Message message = createProtobufMessage(media, context, videoRequest);
            message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
            messages.add(message);
        }

        return messages;
    }


    private static VideoRequest createFeedForwardVideoRequest(Track track, int topConfidenceCount) {

        Collection<Detection> topDetections = getTopConfidenceDetections(track.getDetections(), topConfidenceCount);
        IntSummaryStatistics frameSummaryStats = topDetections.stream()
                .mapToInt(Detection::getMediaOffsetFrame)
                .summaryStatistics();

        DetectionProtobuf.VideoTrack.Builder videoTrackBuilder = DetectionProtobuf.VideoTrack.newBuilder()
                .setStartFrame(frameSummaryStats.getMin())
                .setStopFrame(frameSummaryStats.getMax())
                .setConfidence(track.getExemplar().getConfidence());

        for (Map.Entry<String, String> entry : track.getTrackProperties().entrySet()) {
            videoTrackBuilder.addDetectionPropertiesBuilder()
                    .setKey(entry.getKey())
                    .setValue(entry.getValue());
        }

        for (Detection detection : topDetections) {
            videoTrackBuilder.addFrameLocationsBuilder()
                    .setFrame(detection.getMediaOffsetFrame())
                    .setImageLocation(MediaSegmenter.createImageLocation(detection));
        }

        return VideoRequest.newBuilder()
                .setStartFrame(frameSummaryStats.getMin())
                .setStopFrame(frameSummaryStats.getMax())
                .setFeedForwardTrack(videoTrackBuilder)
                .build();
    }



    private static Collection<Detection> getTopConfidenceDetections(Collection<Detection> allDetections,
                                                                    int topConfidenceCount) {
        if (topConfidenceCount <= 0 || topConfidenceCount >= allDetections.size()) {
            return allDetections;
        }

        Comparator<Detection> confidenceComparator = Comparator
                .comparingDouble(Detection::getConfidence)
                .thenComparing(Comparator.naturalOrder());

        PriorityQueue<Detection> topDetections = new PriorityQueue<>(topConfidenceCount, confidenceComparator);

        Iterator<Detection> allDetectionsIter = allDetections.iterator();
        for (int i = 0; i < topConfidenceCount; i++) {
            topDetections.add(allDetectionsIter.next());
        }

        while (allDetectionsIter.hasNext()) {
            Detection detection = allDetectionsIter.next();
            // Check if current detection is less than the minimum top detection so far
            if (confidenceComparator.compare(detection, topDetections.peek()) > 0) {
                topDetections.poll();
                topDetections.add(detection);
            }
        }
        return topDetections;
    }


    private static int getTopConfidenceCount(DetectionContext context) {
        return context.getAlgorithmProperties()
                .stream()
                .filter(ap -> ap.getPropertyName().equalsIgnoreCase(FEED_FORWARD_TOP_CONFIDENCE_COUNT))
                .mapToInt(ap -> Integer.parseInt(ap.getPropertyValue()))
                .findAny()
                .orElse(0);
    }
}

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
import org.mitre.mpf.wfm.buffers.DetectionProtobuf.AudioTrack;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf.DetectionRequest.AudioRequest;
import org.mitre.mpf.wfm.camel.operations.detection.DetectionContext;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.mitre.mpf.wfm.enums.MpfHeaders;

@Component(AudioMediaSegmenter.REF)
public class AudioMediaSegmenter implements MediaSegmenter {
    private static final Logger log = LoggerFactory.getLogger(AudioMediaSegmenter.class);
    public static final String REF = "audioMediaSegmenter";


    @Override
    public List<Message> createDetectionRequestMessages(Media media, DetectionContext context) {
        log.warn("[Job {}:{}:{}] Media #{} is an audio file and will not be segmented.", context.getJobId(),
                 context.getTaskIndex(), context.getActionIndex(), media.getId());

        if (!context.isFirstDetectionTask() && MediaSegmenter.feedForwardIsEnabled(context)) {
            return createFeedForwardMessages(media, context);
        }

        return Collections.singletonList(
                createProtobufMessage(media, context,
                                      AudioRequest.newBuilder().setStartTime(0).setStopTime(-1).build()));
    }


    private static Message createProtobufMessage(Media media, DetectionContext context,
                                                 AudioRequest audioRequest) {
        DetectionProtobuf.DetectionRequest request = MediaSegmenter
                .initializeRequest(media, context)
                .setDataType(DetectionProtobuf.DetectionRequest.DataType.AUDIO)
                .setAudioRequest(audioRequest)
                .build();

        Message message = new DefaultMessage();
        message.setBody(request);
        message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
        return message;
    }

    private static List<Message> createFeedForwardMessages(Media media, DetectionContext context) {
        List<Message> messages = new ArrayList<>();
        for (Track track : context.getPreviousTracks()) {

            AudioRequest.Builder audioRequest = AudioRequest.newBuilder()
                    .setStartTime(track.getStartOffsetTimeInclusive())
                    .setStopTime(track.getEndOffsetTimeInclusive());

            Detection exemplar = track.getExemplar();

            AudioTrack.Builder audioTrackBuilder = audioRequest.getFeedForwardTrackBuilder()
                    .setConfidence(exemplar.getConfidence())
                    .setStartTime(track.getStartOffsetTimeInclusive())
                    .setStopTime(track.getEndOffsetTimeInclusive());

            for (Map.Entry<String, String> entry : exemplar.getDetectionProperties().entrySet()) {
                audioTrackBuilder.addDetectionPropertiesBuilder()
                        .setKey(entry.getKey())
                        .setValue(entry.getValue());
            }

            Message message = createProtobufMessage(media, context, audioRequest.build());
            message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
            messages.add(message);
        }
        return messages;
    }
}

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
import org.mitre.mpf.wfm.camel.operations.detection.DetectionContext;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.data.entities.persistent.MediaImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.mitre.mpf.wfm.camel.operations.mediainspection.MediaInspectionHelper;

import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;

import org.mitre.mpf.wfm.enums.UriScheme;

import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.camel.operations.mediainspection.*;
import org.mitre.mpf.wfm.util.*;

import org.mitre.mpf.wfm.data.IdGenerator;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;

import org.mitre.mpf.wfm.enums.MpfHeaders;
import javax.inject.Inject;

/**
 * This segmenter returns an empty message collection and warns that the provided {@link Media}
 * does not have a type that is supported.
 */
@Component(DefaultMediaSegmenter.REF)
public class DefaultMediaSegmenter implements MediaSegmenter {
    private static final Logger log = LoggerFactory.getLogger(DefaultMediaSegmenter.class);

    private static MediaInspectionHelper _mediaInspectionHelper;
    private static InProgressBatchJobsService _inProgressJobs;

    public static final String REF = "defaultMediaSegmenter";

    @Inject
    public DefaultMediaSegmenter(MediaInspectionHelper mediaInspectionHelper, InProgressBatchJobsService inProgressJobs)
    {
        _mediaInspectionHelper = mediaInspectionHelper;
        _inProgressJobs = inProgressJobs;
    }

    @Override
    public List<Message> createDetectionRequestMessages(Media media, DetectionContext context) {
        log.warn("[Job {}|{}|{}] Media {} is of the MIME type '{}' and will be processed generically.",
                 context.getJobId(),
                 context.getTaskIndex(),
                 context.getActionIndex(),
                 media.getId(),
                 media.getMimeType());

        if (!context.isFirstDetectionTask() && MediaSegmenter.feedForwardIsEnabled(context)) {
            return createFeedForwardMessages(media, context);
        }

        return Collections.singletonList(
                createProtobufMessage(media, context,
                                      DetectionProtobuf.DetectionRequest.GenericRequest.newBuilder().build()));
    }

    private static Message createProtobufMessage(Media media, DetectionContext context,
                                                 DetectionProtobuf.DetectionRequest.GenericRequest genericRequest) {
        DetectionProtobuf.DetectionRequest detectionRequest = MediaSegmenter.initializeRequest(media, context)
                .setDataType(DetectionProtobuf.DetectionRequest.DataType.UNKNOWN)
                .setGenericRequest(genericRequest)
                .build();

        Message message = new DefaultMessage();
        message.setBody(detectionRequest);
        message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
        return message;
    }

    private static List<Message> createFeedForwardMessages(Media media, DetectionContext context) {
        List<Message> messages = new ArrayList<>();
        for (Track track : context.getPreviousTracks()) {

            DetectionProtobuf.DetectionRequest.GenericRequest.Builder genericRequest = DetectionProtobuf.DetectionRequest.GenericRequest.newBuilder();

            Detection exemplar = track.getExemplar();

            DetectionProtobuf.GenericTrack.Builder genericTrackBuilder = genericRequest.getFeedForwardTrackBuilder()
                    .setConfidence(exemplar.getConfidence());

            boolean processMedia = false;
            String uriStr = "";

            for (Map.Entry<String, String> entry : exemplar.getDetectionProperties().entrySet()) {
                if (entry.getKey().equals("MPF_DERIVATIVE_MEDIA_INSPECTION") && Boolean.valueOf(entry.getValue())) {
                    log.warn("Identified derivative media track.");
                    processMedia = true;
                    continue;
                }

                if (entry.getKey().equals("DERIVATIVE_MEDIA_URI")) {
                    uriStr="file://" + entry.getValue();
                }

                genericTrackBuilder.addDetectionPropertiesBuilder()
                        .setKey(entry.getKey())
                        .setValue(entry.getValue());
            }

            if (processMedia) {

                log.warn("Initializing derivative media from {}. Beginning inspection.", uriStr);

                MediaImpl derivative_media = _inProgressJobs.initMedia(uriStr, ImmutableMap.of("IS_DERIVATIVE_MEDIA", "TRUE"), Collections.emptyMap());

                _inProgressJobs.getJob(context.getJobId()).addDerivativeMedia(derivative_media.getId(), derivative_media);

                log.info("Added media ID {} to job ID {}. Beginning inspection.", derivative_media.getId(), context.getJobId());

                _mediaInspectionHelper.inspectMedia(derivative_media, context.getJobId(), derivative_media.getId());

                log.info("Media ID {} inspection complete.", derivative_media.getId());

                Message message = createProtobufMessage(derivative_media, context, genericRequest.build());
                message.setHeader(MpfHeaders.MEDIA_TYPE, derivative_media.getType().toString());
                messages.add(message);
            } else {
                Message message = createProtobufMessage(media, context, genericRequest.build());
                message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
                messages.add(message);
            }
        }
        return messages;
    }
}

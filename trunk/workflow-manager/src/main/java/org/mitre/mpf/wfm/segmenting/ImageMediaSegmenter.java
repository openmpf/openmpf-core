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
import org.mitre.mpf.wfm.buffers.DetectionProtobuf.DetectionRequest.ImageRequest;
import org.mitre.mpf.wfm.camel.operations.detection.DetectionContext;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.mitre.mpf.wfm.enums.MpfHeaders;

@Component(ImageMediaSegmenter.REF)
public class ImageMediaSegmenter implements MediaSegmenter {
	private static final Logger log = LoggerFactory.getLogger(ImageMediaSegmenter.class);
	public static final String REF = "imageMediaSegmenter";


	@Override
	public List<Message> createDetectionRequestMessages(Media media, DetectionContext context) {

		if (context.isFirstDetectionTask()) {
			return Collections.singletonList(createProtobufMessage(media, context, ImageRequest.getDefaultInstance()));
		}
		else if (MediaSegmenter.feedForwardIsEnabled(context)) {
			return createFeedForwardMessages(media, context);
		}
		else if (!context.getPreviousTracks().isEmpty()) {
			return Collections.singletonList(createProtobufMessage(media, context, ImageRequest.getDefaultInstance()));
		}
		else {
			return Collections.emptyList();
		}
	}


	private static Message createProtobufMessage(Media media, DetectionContext context,
	                                             ImageRequest imageRequest) {
		DetectionProtobuf.DetectionRequest detectionRequest = MediaSegmenter.initializeRequest(media, context)
				.setDataType(DetectionProtobuf.DetectionRequest.DataType.IMAGE)
				.setImageRequest(imageRequest)
				.build();

		Message message = new DefaultMessage();
		message.setBody(detectionRequest);
		message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
		return message;
	}


	private static List<Message> createFeedForwardMessages(Media media, DetectionContext context) {
		List<Message> messages = new ArrayList<>();
		for (Track track : context.getPreviousTracks()) {
			DetectionProtobuf.ImageLocation imageLocation = MediaSegmenter.createImageLocation(track.getExemplar());
			ImageRequest imageRequest = ImageRequest.newBuilder()
					.setFeedForwardLocation(imageLocation)
					.build();
			Message message = createProtobufMessage(media, context, imageRequest);
			message.setHeader(MpfHeaders.MEDIA_TYPE, media.getType().toString());
			messages.add(message);
		}
		return messages;
	}
}


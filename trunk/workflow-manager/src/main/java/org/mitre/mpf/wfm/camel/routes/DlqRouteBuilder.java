/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2016 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2016 The MITRE Corporation                                       *
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

package org.mitre.mpf.wfm.camel.routes;

import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.mitre.mpf.wfm.camel.operations.detection.DetectionDeadLetterProcessor;
import org.mitre.mpf.wfm.enums.MpfEndpoints;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * This builds the routes which read messages from the dead letter queue (DLQ).
 */
@Component
public class DlqRouteBuilder extends RouteBuilder {
	private static final Logger log = LoggerFactory.getLogger(DlqRouteBuilder.class);

	public static final String ENTRY_POINT = MpfEndpoints.DEAD_LETTER_QUEUE;
	public static final String EXIT_POINT = MpfEndpoints.PROCESSED_DLQ_MESSAGES_QUEUE;
	public static final String ROUTE_ID = "DLQ Route";

	private static final String TAP_POINT = "direct:dlqTap";

	private final String entryPoint, exitPoint, tapPoint, routeId;

	public DlqRouteBuilder() {
		this(ENTRY_POINT, EXIT_POINT, TAP_POINT, ROUTE_ID);
	}

	public DlqRouteBuilder(String entryPoint, String exitPoint, String tapPoint, String routeId) {
		this.entryPoint = entryPoint;
		this.exitPoint = exitPoint;
		this.tapPoint = tapPoint;
		this.routeId = routeId;
	}

	@Override
	public void configure() throws Exception {
		log.debug("Configuring route '{}'.", routeId);

		from(entryPoint)
			.routeId(routeId)
			.setExchangePattern(ExchangePattern.InOnly)
			.choice()
				.when(header(MpfHeaders.JMS_REPLY_TO).isEqualTo(MpfEndpoints.COMPLETED_DETECTIONS_REPLY_TO)) // otherwise leave message on the default DLQ (for auditing)
					.wireTap(tapPoint) // send unmodified message to the tap point
					// deserialize protobuf message for readability
					.unmarshal().protobuf(org.mitre.mpf.wfm.buffers.DetectionProtobuf.DetectionRequest.getDefaultInstance()).convertBodyTo(String.class)
					.to(exitPoint) // send to the exit point to indicate it has been processed (and for auditing)
			.end();

		from(tapPoint)
			.process(DetectionDeadLetterProcessor.REF) // generate a detection response protobuf message with an error status
			.to(MpfEndpoints.COMPLETED_DETECTIONS); // send protobuf message to the intended destination to increment the job count
	}
}

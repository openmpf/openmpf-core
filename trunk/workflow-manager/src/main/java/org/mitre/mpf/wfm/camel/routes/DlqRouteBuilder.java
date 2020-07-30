/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2020 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2020 The MITRE Corporation                                       *
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

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf;
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
	public static final String EXIT_POINT = MpfEndpoints.COMPLETED_DETECTIONS;
	public static final String AUDIT_EXIT_POINT = MpfEndpoints.DLQ_PROCESSED_MESSAGES;
	public static final String INVALID_EXIT_POINT = MpfEndpoints.DLQ_INVALID_MESSAGES;
	public static final String ROUTE_ID_PREFIX = "DLQ Route";
	public static final String SELECTOR_REPLY_TO = MpfEndpoints.COMPLETED_DETECTIONS_REPLY_TO;
	public static final String DLQ_DELIVERY_FAILURE_CAUSE_PROPERTY = "dlqDeliveryFailureCause";
	public static final int DUPLICATE_PREFETCH_SIZE = 1500;

	public static final String DUPLICATE_FROM_STORE_MESSAGE = "duplicate from store";
	public static final String DUPLICATE_FROM_CURSOR_MESSAGE = "duplicate paged in from cursor";
	public static final String SUPPRESSING_DUPLICATE_DELIVERY_MESSAGE = "Suppressing duplicate delivery";

	private String entryPoint, exitPoint, auditExitPoint, invalidExitPoint, routeIdPrefix, selectorReplyTo;

	public DlqRouteBuilder() {
		this(ENTRY_POINT, EXIT_POINT, AUDIT_EXIT_POINT, INVALID_EXIT_POINT, ROUTE_ID_PREFIX, SELECTOR_REPLY_TO);
	}

	public DlqRouteBuilder(String entryPoint, String exitPoint, String auditExitPoint, String invalidExitPoint,
						   String routeIdPrefix, String selectorReplyTo) {
		this.entryPoint = entryPoint;
		this.exitPoint = exitPoint;
		this.auditExitPoint = auditExitPoint;
		this.invalidExitPoint = invalidExitPoint;
		this.routeIdPrefix = routeIdPrefix;
		this.selectorReplyTo = selectorReplyTo;
	}

	@Override
	public void configure() throws Exception {

		// Drop duplicate messages to prevent ActiveMQ heap space issues.

		String routeId = routeIdPrefix + " for Duplicate Messages";
		log.debug("Configuring route '{}'.", routeId);

		// Note that "LIKE" is case sensitive.
		String dupCondition = DLQ_DELIVERY_FAILURE_CAUSE_PROPERTY + " LIKE '%" + DUPLICATE_FROM_STORE_MESSAGE + "%'" +
				" OR " + DLQ_DELIVERY_FAILURE_CAUSE_PROPERTY + " LIKE '%" + DUPLICATE_FROM_CURSOR_MESSAGE + "%'" +
				" OR " + DLQ_DELIVERY_FAILURE_CAUSE_PROPERTY + " LIKE '%" + SUPPRESSING_DUPLICATE_DELIVERY_MESSAGE + "%'";
		String selector = "?selector=" + java.net.URLEncoder.encode(dupCondition, "UTF-8");

		from(entryPoint + selector + "&destination.consumer.prefetchSize=" + DUPLICATE_PREFETCH_SIZE)
			.routeId(routeId)
			.setExchangePattern(ExchangePattern.InOnly)
			.stop(); // drop message without processing or forwarding


		// Process detection messages sent to components to mark jobs as complete with errors.

		routeId = routeIdPrefix + " for Completed Detections";
		log.debug("Configuring route '{}'.", routeId);

		// Ensure that this selector and the previous one are mutually exclusive.
		selector = "?selector=(" + java.net.URLEncoder.encode(MpfHeaders.JMS_REPLY_TO + "='" + selectorReplyTo + "') " +
				"AND ((" + DLQ_DELIVERY_FAILURE_CAUSE_PROPERTY + " IS NULL) OR (NOT (" + dupCondition + ")))", "UTF-8");

		from(entryPoint + selector)
			.routeId(routeId)
			.setExchangePattern(ExchangePattern.InOnly)

				// Configure exception handler(s) before behaviors that can cause exceptions.
				.onException(InvalidProtocolBufferException.class)
					.log(LoggingLevel.ERROR, DlqRouteBuilder.class.getName(),
						"Cannot parse message into a DetectionRequest. Cannot mark job ${header." + MpfHeaders.JOB_ID + "} as completed.")
					// ActiveMQ DefaultErrorHandler will print out message details.
					.to(invalidExitPoint) // send possibly corrupted message to a separate queue for auditing
					.handled(true) // prevent further camel processing
				.end()

				// Attempt to deserialize the protobuf message now. Failure to deserialize will trigger the exception
				// handler and prevent further exceptions and redelivery attempts for this message.

				// On at least one occasion, the following exception was observed:
				//     "com.google.protobuf.InvalidProtocolBufferException: Message missing required fields: data_uri"
				// Further debugging is necessary to determine the root cause.

				.unmarshal().protobuf(DetectionProtobuf.DetectionRequest.getDefaultInstance()).convertBodyTo(String.class)
				.multicast()
					.pipeline()
						.to(auditExitPoint) // send deserialized (readable) message to the exit point to indicate it has been processed, and for auditing
					.end()
					.pipeline()
						.process(DetectionDeadLetterProcessor.REF) // generate a detection response protobuf message with an error status
						.to(exitPoint) // send protobuf message to the intended destination to increment the job count
					.end()
				.end()

			.end();


		// Otherwise, leave messages on the default DLQ for auditing.
	}
}

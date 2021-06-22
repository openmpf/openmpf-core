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

package org.mitre.mpf.wfm.camel.routes;

import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.mitre.mpf.wfm.camel.DefaultTaskSplitter;
import org.mitre.mpf.wfm.camel.JobCompleteProcessorImpl;
import org.mitre.mpf.wfm.enums.MpfEndpoints;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * This builds the routes which replace the MPF Action Router.
 */
@Component
public class JobRouterRouteBuilder extends RouteBuilder {
	private static final Logger log = LoggerFactory.getLogger(JobRouterRouteBuilder.class);

	public static final String ENTRY_POINT = "jms:MPF.JOB_ROUTER";
	public static final String ROUTE_ID = "Job Router Route";

	private final String entryPoint, routeId;

	public JobRouterRouteBuilder() {
		this(ENTRY_POINT, ROUTE_ID);
	}

	public JobRouterRouteBuilder(String entryPoint, String routeId) {
		this.entryPoint = entryPoint;
		this.routeId = routeId;
	}


	@Override
	public void configure() {
		log.debug("Configuring route '{}'.", routeId);

		from(entryPoint)
			.routeId(routeId)
			.setExchangePattern(ExchangePattern.InOnly)
			.choice()
				.when(header(MpfHeaders.JOB_COMPLETE).isEqualTo(true))
                    .process(JobCompleteProcessorImpl.REF)
				.otherwise()
					.split().method(DefaultTaskSplitter.REF, "split")
						.parallelProcessing() // Create work units and process them in any order.
						.streaming() // Aggregate responses in any order.
						.choice()
							.when(header(MpfHeaders.EMPTY_SPLIT).isEqualTo(Boolean.TRUE))
								.removeHeader(MpfHeaders.EMPTY_SPLIT)
								.to(MpfEndpoints.TASK_RESULTS_AGGREGATOR)
							.otherwise()
								.marshal().protobuf()
								.recipientList(header(MpfHeaders.RECIPIENT_QUEUE))
				.endChoice()
					.endChoice() // For unknown reasons, the split() DSL is ended by 'endChoice'.
				.end()
			.endChoice();
	}
}

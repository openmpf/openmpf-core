/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2017 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2017 The MITRE Corporation                                       *
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

package org.mitre.mpf.nms.streaming.messages;

import org.apache.commons.lang3.builder.RecursiveToStringStyle;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

import java.io.Serializable;
import java.util.Map;

@SuppressWarnings("PublicField")
public class StreamingJobLaunchMessage implements StreamingJobMessage, Serializable {

	public final long jobId;

	public final String streamUri;

	public final int segmentSize;

	public final double stallTimeout;

	public final int stallAlertThreshold;

	public final String componentName;

	public final String componentLibraryPath;

	public final Map<String, String> componentEnvironmentVariables;

	public final Map<String, String> jobProperties;

	public final Map<String, String> mediaProperties;

	public final String messageBrokerUri;

	public final String jobStatusQueue;

	public final String activityAlertQueue;

	public final String summaryReportQueue;

	//TODO: For future use. Untested.
//	public final FrameReaderLaunchMessage frameReaderLaunchMessage;
//
//	public final VideoWriterLaunchMessage videoWriterLaunchMessage;
//
//	public final List<ComponentLaunchMessage> componentLaunchMessages;
//
//
	//TODO: For future use. Untested.
//	public StreamingJobLaunchMessage(long jobId, FrameReaderLaunchMessage frameReaderLaunchMessage,
//	                                 VideoWriterLaunchMessage videoWriterLaunchMessage,
//	                                 List<ComponentLaunchMessage> componentLaunchMessages) {
//		this.jobId = jobId;
//		this.frameReaderLaunchMessage = frameReaderLaunchMessage;
//		this.videoWriterLaunchMessage = videoWriterLaunchMessage;
//		this.componentLaunchMessages = componentLaunchMessages;
//	}

	public StreamingJobLaunchMessage(
			long jobId,
			String streamUri,
			int segmentSize,
			double stallTimeout,
			int stallAlertThreshold,
			String componentName,
			String componentLibraryPath,
			Map<String, String> componentEnvironmentVariables,
			Map<String, String> jobProperties,
			Map<String, String> mediaProperties,
			String messageBrokerUri,
			String jobStatusQueue,
			String activityAlertQueue,
			String summaryReportQueue) {

		this.jobId = jobId;
		this.streamUri = streamUri;
		this.segmentSize = segmentSize;
		this.stallTimeout = stallTimeout;
		this.stallAlertThreshold = stallAlertThreshold;
		this.componentName = componentName;
		this.componentLibraryPath = componentLibraryPath;
		this.componentEnvironmentVariables = componentEnvironmentVariables;
		this.jobProperties = jobProperties;
		this.mediaProperties = mediaProperties;
		this.messageBrokerUri = messageBrokerUri;
		this.jobStatusQueue = jobStatusQueue;
		this.activityAlertQueue = activityAlertQueue;
		this.summaryReportQueue = summaryReportQueue;
	}


	@Override
	public String toString() {
		return ReflectionToStringBuilder.toString(this, new RecursiveToStringStyle());
	}
}

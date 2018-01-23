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


package org.mitre.mpf.mst;

import org.jgroups.Address;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mitre.mpf.nms.MasterNode;
import org.mitre.mpf.wfm.businessrules.StreamingJobRequestBo;
import org.mitre.mpf.wfm.data.entities.transients.*;
import org.mitre.mpf.wfm.enums.ActionType;
import org.mitre.mpf.wfm.enums.JobStatus;
import org.mitre.mpf.wfm.service.StreamingJobMessageSender;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.net.URL;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertEquals;
import static org.mockito.AdditionalMatchers.*;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;


@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(initializers = TestSystemWithDefaultConfig.AppCtxInit.class)
@ActiveProfiles("jenkins")
@DirtiesContext // Make sure TestStreamingJobStartStop does not use same application context as other tests.
public class TestStreamingJobStartStop {

	private static final Logger LOG = LoggerFactory.getLogger(TestStreamingJobStartStop.class);

	private static final StreamingJobRequestBo _mockStreamingJobRequestBo = mock(StreamingJobRequestBo.class);

	@Configuration
	public static class TestConfig {

		@Bean
		@Primary
		public StreamingJobRequestBo streamingJobRequestBo() {
			return _mockStreamingJobRequestBo;
		}
	}

	@Autowired
	private StreamingJobMessageSender _jobSender;

	@Autowired
	private MasterNode _masterNode;



	@Test
	public void testJobStartStop() {
		long jobId = 43231;
		long test_start_time = System.currentTimeMillis();


		List<Address> currentNodeManagerHosts = _masterNode.getCurrentNodeManagerHosts();
		Map<String, Boolean> configuredManagerHosts = _masterNode.getConfiguredManagerHosts();

		String currentHostList = currentNodeManagerHosts.stream()
				.map(Object::toString)
				.collect(joining("\n"));
		LOG.info("MasterNode.getCurrentNodeManagerHosts():\n{}", currentHostList);

		String configuredHostList = configuredManagerHosts.entrySet()
				.stream()
				.map(Object::toString)
				.collect(joining("\n"));

		LOG.info("MasterNode.getConfiguredManagerHosts():\n{}", configuredHostList);



		TransientStage stage1 = new TransientStage("stage1", "description", ActionType.DETECTION);
		stage1.getActions().add(new TransientAction("Action1", "description", "HelloWorld"));

		TransientPipeline pipeline = new TransientPipeline("HELLOWORLD SAMPLE PIPELINE", "desc");
		pipeline.getStages().add(stage1);


		URL videoUrl = getClass().getResource("/samples/face/new_face_video.avi");
		TransientStream stream = new TransientStream(124, videoUrl.toString());
		stream.setSegmentSize(10);
		TransientStreamingJob streamingJob = new TransientStreamingJob(
				jobId, "ext id", pipeline, 1, 1, false, "mydir",
				false);
		streamingJob.setStream(stream);

		_jobSender.launchJob(streamingJob);

		verify(_mockStreamingJobRequestBo, timeout(30_000).atLeastOnce())
				.handleNewActivityAlert(eq(jobId), geq(0L), gt(test_start_time));

		_jobSender.stopJob(jobId);


		verify(_mockStreamingJobRequestBo, timeout(30_000))
				.jobCompleted(eq(jobId), or(eq(JobStatus.TERMINATED), eq(JobStatus.CANCELLED)));

		ArgumentCaptor<SegmentSummaryReport> reportCaptor = ArgumentCaptor.forClass(SegmentSummaryReport.class);

		verify(_mockStreamingJobRequestBo, timeout(30_000).atLeastOnce())
				.handleNewSummaryReport(reportCaptor.capture());

		SegmentSummaryReport summaryReport = reportCaptor.getValue();
		assertEquals(jobId, summaryReport.getJobId());
	}
}

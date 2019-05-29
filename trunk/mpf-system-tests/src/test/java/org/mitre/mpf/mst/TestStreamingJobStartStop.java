/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2019 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2019 The MITRE Corporation                                       *
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.tuple.Pair;
import org.jgroups.Address;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mitre.mpf.interop.*;
import org.mitre.mpf.nms.AddressParser;
import org.mitre.mpf.nms.MasterNode;
import org.mitre.mpf.nms.NodeTypes;
import org.mitre.mpf.wfm.businessrules.StreamingJobRequestBo;
import org.mitre.mpf.wfm.data.entities.persistent.StreamingJobStatus;
import org.mitre.mpf.wfm.data.entities.transients.*;
import org.mitre.mpf.wfm.enums.ActionType;
import org.mitre.mpf.wfm.enums.StreamingJobStatusType;
import org.mitre.mpf.wfm.service.StreamingJobMessageSender;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.*;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;


@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(initializers = TestSystemWithDefaultConfig.AppCtxInit.class)
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

	@Autowired
	private ObjectMapper _objectMapper;

	@Autowired
	protected IoUtils _ioUtils;


	@Test(timeout = 5 * 60_000)
	public void testJobStartStop() throws InterruptedException {
		long jobId = 43231;
		long test_start_time = System.currentTimeMillis();

		waitForCorrectNodes();

		TransientStreamingJob streamingJob = createJob(
				jobId,
				"SUBSENSE",
				"SUBSENSE MOTION DETECTION (WITH TRACKING) PIPELINE",
				"/samples/face/new_face_video.avi",
				10,
				1);

		_jobSender.launchJob(streamingJob);

		verify(_mockStreamingJobRequestBo, timeout(30_000).atLeastOnce())
				.handleNewActivityAlert(eq(jobId), geq(0L), gt(test_start_time));

		_jobSender.stopJob(jobId);


		verify(_mockStreamingJobRequestBo, timeout(30_000))
				.handleJobStatusChange(eq(jobId),
				                       hasStatus(StreamingJobStatusType.TERMINATED, StreamingJobStatusType.CANCELLED),
				                       gt(test_start_time));

		ArgumentCaptor<JsonSegmentSummaryReport> reportCaptor = ArgumentCaptor.forClass(JsonSegmentSummaryReport.class);

		verify(_mockStreamingJobRequestBo, timeout(30_000).atLeastOnce())
				.handleNewSummaryReport(reportCaptor.capture());

		JsonSegmentSummaryReport summaryReport = reportCaptor.getValue();
		assertEquals(jobId, summaryReport.getJobId());

		boolean hasNonEmptyDetection = summaryReport.getTypes()
				.values()
				.stream()
				.flatMap(Collection::stream)
				.flatMap(t -> t.getDetections().stream())
				.anyMatch(d -> d.getHeight() > 0 && d.getWidth() > 0);

		assertTrue(hasNonEmptyDetection);
	}


	@Test(timeout = 5 * 60_000)
	public void testDarknetStreaming() throws InterruptedException, IOException {
		long jobId = 43234;
		int segmentSize = 10;
		long test_start_time = System.currentTimeMillis();

		waitForCorrectNodes();

		TransientStreamingJob streamingJob = createJob(
				jobId,
				"DARKNET",
				"TINY YOLO OBJECT DETECTION PIPELINE",
				"/samples/face/video_01.mp4",
				segmentSize,
				0);

		_jobSender.launchJob(streamingJob);

		verify(_mockStreamingJobRequestBo, timeout(60_000).atLeastOnce())
				.handleNewActivityAlert(eq(jobId), geq(0L), gt(test_start_time));


		ArgumentCaptor<JsonSegmentSummaryReport> reportCaptor = ArgumentCaptor.forClass(JsonSegmentSummaryReport.class);
		verify(_mockStreamingJobRequestBo, timeout(60_000).atLeastOnce())
				.handleNewSummaryReport(reportCaptor.capture());

		_jobSender.stopJob(jobId);


		verify(_mockStreamingJobRequestBo, timeout(60_000))
				.handleJobStatusChange(eq(jobId),
				                       hasStatus(StreamingJobStatusType.TERMINATED, StreamingJobStatusType.CANCELLED),
				                       gt(test_start_time));


		JsonSegmentSummaryReport summaryReport = reportCaptor.getValue();
		assertEquals(jobId, summaryReport.getJobId());

		List<JsonStreamingTrackOutputObject> actualTracks = summaryReport.getTypes()
				.values()
				.stream()
				.flatMap(Collection::stream)
				.collect(toList());

		assertTrue(actualTracks.stream()
				.map(t -> t.getTrackProperties().get("CLASSIFICATION"))
				.anyMatch("person"::equalsIgnoreCase));


		URL expectedOutputPath = getClass().getClassLoader()
				.getResource("output/object/runDarknetDetectVideo.json");
		JsonOutputObject expectedOutputJson = _objectMapper.readValue(expectedOutputPath, JsonOutputObject.class);

		boolean allExpectedTracksFound = expectedOutputJson.getMedia()
				.stream()
				.flatMap(m -> m.getTypes().values().stream())
				.flatMap(Collection::stream)
				.flatMap(a -> a.getTracks().stream())
				.filter(t -> t.getStopOffsetFrame() < segmentSize)
				.allMatch(t -> containsMatchingTrack(t, actualTracks));
		assertTrue(allExpectedTracksFound);
	}


	private static StreamingJobStatus hasStatus(StreamingJobStatusType... statuses) {
		return argThat(status -> Stream.of(statuses)
				.anyMatch(t -> status.getType() == t));
	}

	private TransientStreamingJob createJob(long jobId, String algorithm, String pipelineName,
	                                               String mediaPath, int segmentSize, int stallTimeout) {

		TransientStage stage1 = new TransientStage(
				"stage1",
				"description",
				ActionType.DETECTION,
				Collections.singletonList(new TransientAction("Action1", "description", algorithm, Collections.emptyMap())));

		TransientPipeline pipeline = new TransientPipeline(pipelineName, "desc",
		                                                   Collections.singletonList(stage1));
		URI videoUri = _ioUtils.findFile(mediaPath);
		TransientStream stream = new TransientStream(124, videoUri.toString(), segmentSize, Collections.emptyMap());

		return new TransientStreamingJobImpl(
				jobId, "ext id", pipeline, stream, 1, stallTimeout, false, "mydir",
				null, null, Collections.emptyMap(), Collections.emptyMap());
	}


	private static boolean containsMatchingTrack(JsonTrackOutputObject expectedTrack,
	                                             Collection<JsonStreamingTrackOutputObject> actualTracks) {
		return actualTracks.stream()
				.anyMatch(actual -> tracksMatch(expectedTrack, actual));
	}

	private static boolean tracksMatch(JsonTrackOutputObject expectedTrack,
	                                   JsonStreamingTrackOutputObject actualTrack) {
		return actualTrack.getStartOffsetFrame() == expectedTrack.getStartOffsetFrame()
				&& actualTrack.getStopOffsetFrame() == expectedTrack.getStopOffsetFrame()
				&& Math.abs(actualTrack.getConfidence() - expectedTrack.getConfidence()) < 0.01
				&& actualTrack.getTrackProperties().equals(expectedTrack.getTrackProperties())
				&& exemplarsMatch(expectedTrack, actualTrack);
	}

	private static boolean exemplarsMatch(JsonTrackOutputObject expectedTrack,
	                                      JsonStreamingTrackOutputObject actualTrack) {
		JsonDetectionOutputObject expectedExemplar = expectedTrack.getExemplar();
		JsonStreamingDetectionOutputObject actualExemplar = actualTrack.getExemplar();

		return expectedExemplar.getX() == actualExemplar.getX()
				&& expectedExemplar.getY() == actualExemplar.getY()
				&& expectedExemplar.getWidth() == actualExemplar.getWidth()
				&& expectedExemplar.getHeight() == actualExemplar.getHeight()
				&& Math.abs(expectedExemplar.getConfidence() - actualExemplar.getConfidence()) < 0.01
				&& expectedExemplar.getOffsetFrame() == actualExemplar.getOffsetFrame()
				&& expectedExemplar.getDetectionProperties().equals(actualExemplar.getDetectionProperties());
	}


	private void waitForCorrectNodes() throws InterruptedException {
		while (!hasCorrectNodes()) {
			Thread.sleep(1000);
		}
	}

	// Sometimes when the test starts there are 2 master nodes.
	// This makes sure there is only one master node and only one child node.
	private boolean hasCorrectNodes() {
		List<Address> currentNodes = _masterNode.getCurrentNodeManagerHosts();
		Map<NodeTypes, Long> nodeTypeCounts = currentNodes
				.stream()
				.map(AddressParser::parse)
				.collect(groupingBy(Pair::getRight, counting()));

		long masterNodeCount = nodeTypeCounts.getOrDefault(NodeTypes.MasterNode, 0L);
		long childNodeCount = nodeTypeCounts.getOrDefault(NodeTypes.NodeManager, 0L);
		if (masterNodeCount == 1 && childNodeCount == 1) {
			return true;
		}

		String currentNodeList = currentNodes.stream()
				.map(Object::toString)
				.collect(joining("\n"));

		LOG.warn("Current Nodes:\n{}", currentNodeList);

		if (masterNodeCount != 1) {
			LOG.warn("Incorrect number of master nodes. Expected 1 but there were {}.", masterNodeCount);
		}
		if (childNodeCount != 1) {
			LOG.warn("Incorrect number of child nodes. Expected 1 but there were {}.", childNodeCount);
		}
		return false;
	}
}

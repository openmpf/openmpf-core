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

package org.mitre.mpf.wfm.segmenting;

import com.google.common.collect.ImmutableSortedMap;
import org.apache.camel.Message;
import org.junit.Test;
import org.mitre.mpf.test.TestUtil;
import org.mitre.mpf.wfm.buffers.AlgorithmPropertyProtocolBuffer;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf.DetectionRequest;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf.ImageLocation;
import org.mitre.mpf.wfm.buffers.DetectionProtobuf.PropertyMap;
import org.mitre.mpf.wfm.camel.operations.detection.DetectionContext;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.util.TimePair;

import java.util.*;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestMediaSegmenter {

	@Test
	public void testNoSplits() {
		List<TimePair> results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 24)), 25, 1, 100);
		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(24,  results.get(0).getEndInclusive());
	}

	@Test
	public void testSplits() {
		List<TimePair> results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 24)), 10, 1, 100);

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(9, results.get(0).getEndInclusive());

		assertEquals(10, results.get(1).getStartInclusive());
		assertEquals(19, results.get(1).getEndInclusive());

		assertEquals(20, results.get(2).getStartInclusive());
		assertEquals(24, results.get(2).getEndInclusive());


		// "Segmenting Media" example from User Guide
		List<TimePair> inputs = new ArrayList<>();
		inputs.add(new TimePair(25, 150));
		inputs.add(new TimePair(175, 275));
		inputs.add(new TimePair(25, 125));
		inputs.add(new TimePair(0, 175));

		results = MediaSegmenter.createSegments(
				inputs, 100, 5, 0);

		assertEquals(3, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(199, results.get(1).getEndInclusive());

		assertEquals(200, results.get(2).getStartInclusive());
		assertEquals(275, results.get(2).getEndInclusive());
	}

	@Test
	public void testShortFirstSegment() {
		List<TimePair> results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 3)), 10, 5, 0);

		assertEquals(1, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(3, results.get(0).getEndInclusive());
	}

	@Test
	public void testShortAdjacentSegment() {
		List<TimePair> inputs = new ArrayList<>();
		inputs.add(new TimePair(0, 9));
		inputs.add(new TimePair(10, 13));

		List<TimePair> results = MediaSegmenter.createSegments(
				inputs, 10, 5, 0);

		assertEquals(2, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(9, results.get(0).getEndInclusive());

		assertEquals(10, results.get(1).getStartInclusive());
		assertEquals(13, results.get(1).getEndInclusive());


		// "Adjacent Segment Present" example from User Guide
		inputs.clear();
		inputs.add(new TimePair(0, 99));
		inputs.add(new TimePair(100, 199));
		inputs.add(new TimePair(200, 249));

		results = MediaSegmenter.createSegments(
				inputs, 100, 75, 50);

		assertEquals(2, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(249, results.get(1).getEndInclusive());
	}

	@Test
	public void testShortNonAdjacentSegment() {
		List<TimePair> inputs = new ArrayList<>();
		inputs.add(new TimePair(0, 11));
		inputs.add(new TimePair(13, 13));

		List<TimePair> results = MediaSegmenter.createSegments(
				inputs, 10, 5, 0);

		assertEquals(2, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(11, results.get(0).getEndInclusive());

		assertEquals(13, results.get(1).getStartInclusive());
		assertEquals(13, results.get(1).getEndInclusive());


		// "No Adjacent Segment" example from User Guide
		inputs.clear();
		inputs.add(new TimePair(0, 99));
		inputs.add(new TimePair(100, 199));
		inputs.add(new TimePair(200, 249));
		inputs.add(new TimePair(325, 349));

		results = MediaSegmenter.createSegments(
				inputs, 100, 75, 50);

		assertEquals(3, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(249, results.get(1).getEndInclusive());

		assertEquals(325, results.get(2).getStartInclusive());
		assertEquals(349, results.get(2).getEndInclusive());
	}

	@Test
	public void testSegmentLengthAtLeastMinLength() {
		List<TimePair> results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 199)), 100, 10, 0);

		assertEquals(2, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(199, results.get(1).getEndInclusive()); // perfect split


		results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 200)), 100, 10, 0);

		assertEquals(2, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(200, results.get(1).getEndInclusive()); // short segment (lower bound) merged into preceding segment


		results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 208)), 100, 10, 0);

		assertEquals(2, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(208, results.get(1).getEndInclusive()); // short segment (upper bound) merged into preceding segment


		results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 209)), 100, 10, 0);

		assertEquals(3, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(199, results.get(1).getEndInclusive());

		assertEquals(200, results.get(2).getStartInclusive());
		assertEquals(209, results.get(2).getEndInclusive()); // short segment (lower bound) stands by itself


		results = MediaSegmenter.createSegments(
				Collections.singletonList(new TimePair(0, 298)), 100, 10, 0);

		assertEquals(3, results.size());

		assertEquals(0, results.get(0).getStartInclusive());
		assertEquals(99, results.get(0).getEndInclusive());

		assertEquals(100, results.get(1).getStartInclusive());
		assertEquals(199, results.get(1).getEndInclusive());

		assertEquals(200, results.get(2).getStartInclusive());
		assertEquals(298, results.get(2).getEndInclusive()); // short segment (upper bound) stands by itself
	}

	@Test
	public void testMinGapBetweenSegments() {
		List<TimePair> inputs = new ArrayList<>();
		inputs.add(new TimePair(0, 149));
		inputs.add(new TimePair(175, 399));
		inputs.add(new TimePair(500, 899));

		List<TimePair> results = MediaSegmenter.createSegments(
				inputs, 75, 25, 100);

		assertEquals(12, results.size());

		int offset = 0;
		for (TimePair result : results.subList(0, 5)) {
			assertEquals(offset, result.getStartInclusive());
			assertEquals(offset+74, result.getEndInclusive());
			offset += 75;
		}

		assertEquals(offset, results.get(5).getStartInclusive());
		assertEquals(offset+24, results.get(5).getEndInclusive());

		offset = 500;
		for (TimePair result : results.subList(6, 11)) {
			assertEquals(offset, result.getStartInclusive());
			assertEquals(offset+74, result.getEndInclusive());
			offset += 75;
		}

		assertEquals(offset, results.get(11).getStartInclusive());
		assertEquals(offset+24, results.get(11).getEndInclusive());


		// "MIN_GAP_BETWEEN_SEGMENTS" Property" example from User Guide (after track merging)
		inputs.clear();
		inputs.add(new TimePair(0, 149));
		inputs.add(new TimePair(175, 399));
		inputs.add(new TimePair(500, 899));

		results = MediaSegmenter.createSegments(
				inputs, 75, 26, 100);

		assertEquals(10, results.size());

		offset = 0;
		for (TimePair result : results.subList(0, 4)) {
			assertEquals(offset, result.getStartInclusive());
			assertEquals(offset+74, result.getEndInclusive());
			offset += 75;
		}

		assertEquals(offset, results.get(4).getStartInclusive());
		assertEquals(offset+99, results.get(4).getEndInclusive());

		offset = 500;
		for (TimePair result : results.subList(5, 9)) {
			assertEquals(offset, result.getStartInclusive());
			assertEquals(offset+74, result.getEndInclusive());
			offset += 75;
		}

		assertEquals(offset, results.get(9).getStartInclusive());
		assertEquals(offset+99, results.get(9).getEndInclusive());
	}

	@Test
	public void testOverlap() {
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(10, 20), 0));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(10, 20), 1));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(10, 20), 10));

		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(15, 20), 0));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(15, 20), 1));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(15, 20), 10));

		assertFalse(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(16, 20), 0));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(16, 20), 1));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(16, 20), 10));


		assertFalse(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(20, 40), 0));
		assertFalse(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(20, 40), 1));
		assertFalse(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(20, 40), 4));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(20, 40), 5));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(20, 40), 6));
		assertTrue(MediaSegmenter.overlaps(new TimePair(0, 15), new TimePair(20, 40), 10));
	}

	@Test
	public void testMerge() {
		assertEquals(new TimePair(0, 20), MediaSegmenter.merge(new TimePair(0, 15), new TimePair(10, 20)));
	}



	protected static void assertContainsAlgoProperty(String key, String value, Collection<DetectionRequest> requests) {
		for (DetectionRequest request : requests) {
			assertTrue(
					String.format("Expected request to contain algorithm property: %s: %s", key, value),
					request.getAlgorithmPropertyList().stream()
							.anyMatch(ap -> ap.getPropertyName().equals(key) && ap.getPropertyValue().equals(value)));
		}
	}



	protected static void assertContainsExpectedMediaMetadata(Collection<DetectionRequest> requests) {
		assertTrue("Expected each request to contain 1 media metadata field", requests.stream()
				.allMatch(dr -> dr.getMediaMetadataList().size() == 1));
		assertContainsMediaMetadata("mediaKey1", "mediaValue1", requests);

	}

	protected static void assertContainsMediaMetadata(String key, String value, Collection<DetectionRequest> requests) {
		for (DetectionRequest request : requests) {
			assertTrue(
					String.format("Expected request to contain media metadata: %s: %s", key, value),
					request.getMediaMetadataList().stream()
							.anyMatch(mp -> mp.getKey().equals(key) && mp.getValue().equals(value)));
		}
	}


	protected static boolean confidenceIsEqualToDimensions(float confidence, ImageLocation imageLocation) {
		int dimensions = (int) confidence;
		return TestUtil.almostEqual(imageLocation.getConfidence(), confidence)
				&& dimensions == imageLocation.getXLeftUpper()
				&& dimensions == imageLocation.getYLeftUpper()
				&& dimensions == imageLocation.getWidth()
				&& dimensions == imageLocation.getHeight()
				&& containsExpectedDetectionProperties(dimensions, imageLocation.getDetectionPropertiesList());
	}

	protected static DetectionContext createTestDetectionContext(int stage, Map<String, String> additionalAlgoProps,
	                                                           Set<Track> tracks) {
		return new DetectionContext(
				1, stage, "STAGE_NAME", 0, "ACTION_NAME", stage == 0,
				createTestAlgorithmProperties(additionalAlgoProps), tracks,
				createTestSegmentingPlan());
	}


	private static List<AlgorithmPropertyProtocolBuffer.AlgorithmProperty> createTestAlgorithmProperties(
			Map<String, String> additionalAlgoProps) {
		Map<String, String> algoProps = new HashMap<>();
		algoProps.put("algoKey1", "algoValue1");
		algoProps.put("algoKey2", "algoValue2");
		algoProps.putAll(additionalAlgoProps);


		return algoProps.entrySet().stream()
				.map(e -> AlgorithmPropertyProtocolBuffer.AlgorithmProperty.newBuilder()
						.setPropertyName(e.getKey())
						.setPropertyValue(e.getValue())
						.build())
				.collect(toList());
	}


	private static SegmentingPlan createTestSegmentingPlan() {
		return new SegmentingPlan(20, 1, 1,1);
	}



	protected static SortedMap<String, String> createDetectionProperties(int detectionNumber) {
		return ImmutableSortedMap.of("detectionKey" + detectionNumber, "detectionValue" + detectionNumber);
	}


	protected static boolean containsExpectedDetectionProperties(
			int detectionNumber, Collection<PropertyMap> properties) {

		SortedMap<String, String> expectedProperties = createDetectionProperties(detectionNumber);
		if (expectedProperties.size() != properties.size()) {
			return false;
		}

		for (PropertyMap property : properties) {
			String expectedValue = expectedProperties.get(property.getKey());
			if (!expectedValue.equals(property.getValue())) {
				return false;
			}
		}
		return true;
	}


	protected static Detection createDetection(int frame, float confidence) {
		int dimensions = (int) confidence;
		return new Detection(dimensions, dimensions, dimensions, dimensions, confidence, frame, 1,
		                     createDetectionProperties(dimensions));
	}


	protected static Track createTrack(Detection... detections) {
		List<Detection> detectionList = Arrays.asList(detections);
		int start = detectionList.stream()
				.mapToInt(Detection::getMediaOffsetFrame)
				.min()
				.getAsInt();
		int stop = detectionList.stream()
				.mapToInt(Detection::getMediaOffsetFrame)
				.max()
				.getAsInt();

		Detection exemplar = detectionList.stream()
				.max(Comparator.comparing(Detection::getConfidence))
				.get();

		Track track = new Track(1, 1, 1, 0, start, stop, 0, 0, "type",
		                        exemplar.getConfidence(), detectionList, Collections.emptyMap());
		return track;
	}



	protected static List<DetectionRequest> unwrapMessages(Collection<Message> messages) {
		return messages
				.stream()
				.map(m -> m.getBody(DetectionRequest.class))
				.collect(toList());
	}
}

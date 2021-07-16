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

package org.mitre.mpf.wfm.camelOps;

import org.junit.Test;
import org.mitre.mpf.wfm.camel.operations.detection.padding.DetectionPaddingProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.enums.IssueCodes;
import org.mitre.mpf.wfm.enums.MediaType;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.mitre.mpf.wfm.util.ObjectMapperFactory;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.apache.commons.collections4.CollectionUtils.isEqualCollection;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

public class TestDetectionPaddingProcessor {

    @Test
    public void testPaddingWithoutRotation() {
        assertPadding(3, 3, 4, 4, "25%", "25%", 10, 10, 2, 2, 6, 6);   // expand uniformly
        assertPadding(3, 3, 4, 4, "0%", "25%", 10, 10, 3, 2, 4, 6);    // expand height only
        assertPadding(3, 3, 4, 4, "25%", "0%", 10, 10, 2, 3, 6, 4);    // expand width only
        assertPadding(3, 3, 4, 4, "0", "0", 10, 10, 3, 3, 4, 4);       // no-op

        assertPadding(5, 5, 4, 4, "50%", "50%", 10, 10, 3, 3, 7, 7);   // over-expand towards bottom right
        assertPadding(1, 1, 4, 4, "50%", "50%", 10, 10, 0, 0, 7, 7);   // over-expand towards top left
        assertPadding(1, 5, 4, 4, "50%", "50%", 10, 10, 0, 3, 7, 7);   // over-expand towards bottom left
        assertPadding(5, 1, 4, 4, "50%", "50%", 10, 10, 3, 0, 7, 7);   // over-expand towards top right

        assertPadding(5, 5, 4, 4, "25%", "75%", 10, 10, 4, 2, 6, 8);   // over-expand towards bottom
        assertPadding(5, 5, 4, 4, "25%", "300%", 10, 10, 4, 0, 6, 10); // over-expand towards top and bottom

        assertPadding(5, 5, 4, 4, "75%", "25%", 10, 10, 2, 4, 8, 6);   // over-expand towards right
        assertPadding(5, 5, 4, 4, "300%", "25%", 10, 10, 0, 4, 10, 6); // over-expand towards left and right

        assertPadding(3, 3, 4, 4, "1", "25%", 10, 10, 2, 2, 6, 6); // expand uniformly
        assertPadding(40, 40, 20, 20, "200%", "200%", 100, 100, 0, 0, 100, 100); // expand uniformly
        assertPadding(40, 40, 20, 20, "40", "40", 100, 100, 0, 0, 100, 100);     // expand uniformly
        assertPadding(40, 40, 20, 20, "200%", "40", 100, 100, 0, 0, 100, 100);   // expand uniformly

        assertPadding(40, 40, 20, 20, "-25%", "-25%", 100, 100, 45, 45, 10, 10); // shrink uniformly
        assertPadding(40, 40, 20, 20, "-25%", "-5", 100, 100, 45, 45, 10, 10);   // shrink uniformly
        assertShrinkToNothing(40, 40, 20, 20, "-50%", "-50%", 100, 100, 50, 50, 1, 1);   // shrink to nothing
        assertShrinkToNothing(40, 40, 20, 20, "-100%", "-277", 100, 100, 50, 50, 1, 1);  // shrink beyond nothing
        assertShrinkToNothing(40, 40, 20, 20, "-500%", "-500%", 100, 100, 50, 50, 1, 1); // shrink beyond nothing
        assertShrinkToNothing(40, 40, 20, 20, "-500%", "-25%", 100, 100, 50, 45, 1, 10); // shrink beyond nothing (width)
        assertShrinkToNothing(40, 40, 20, 20, "-25%", "-500%", 100, 100, 45, 50, 10, 1); // shrink beyond nothing (height)

        assertPadding(40, 40, 20, 20, "100%", "-5", 100, 100, 20, 45, 60, 10); // expand and shrink

        assertShrinkToNothing(20, 20, 7, 7, "-100%", "-100%", 40, 40, 23, 23, 1, 1); // shrink beyond nothing (not exact)
        assertShrinkToNothing(20, 20, 7, 7, "-7", "-7", 40, 40, 23, 23, 1, 1);       // shrink beyond nothing (not exact)
        assertPadding(20, 20, 7, 7, "25%", "25%", 40, 40, 18, 18, 11, 11); // expand uniformly (not exact)
        assertPadding(20, 20, 7, 7, "21%", "21%", 40, 40, 18, 18, 10, 10); // expand uniformly (not exact)
        assertPadding(20, 20, 7, 7, "-21%", "-21%", 40, 40, 21, 21, 5, 5); // shrink uniformly (not exact)

        assertPadding(6, 4, 4, 6, "50%", "33.3%", 10, 20, 4, 2, 6, 10); // over-expand towards right (height != width)
        assertPadding(6, 4, 4, 6, "50%", "33.3%", 20, 10, 4, 2, 8, 8);  // over-expand towards bottom (height != width)
    }


    @Test
    public void testRotatedClippingOrthogonal() {
        {
            Detection input = createDetection(0, 479, 480, 640, "90");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "200%", "200%", 640, 480, input);
            // Input detection is already at max possible size.
            assertEquals(input, actual);
        }

        {
            Detection input = createDetection(300, 100, 400, 100, "90");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "50%", "0", 500, 500, input);
            Detection expected = createDetection(300, 300, 301, 100, "90");
            assertEquals(expected, actual);
        }
    }


    @Test
    public void testRotatedClippingNonOrthogonal() {
        {
            Detection input = createDetection(30, 40, 580, 400, "20");
            Detection padded = DetectionPaddingProcessor.padDetection(
                    "1000%", "1000%", 640, 480, input);
            Detection expected = createDetection(-153, 56, 766, 670, "20");
            assertEquals(expected, padded);
        }

        {
            Detection input = createDetection(300, 100, 400, 100, "45");
            Detection padded = DetectionPaddingProcessor.padDetection(
                    "0", "0", 500, 500, input);
            Detection expected = createDetection(300, 100, 213, 100, "45");
            assertEquals(expected, padded);
        }

        {
            Detection input = createDetection(300, 100, 400, 100, "45");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "50%", "0", 500, 500, input);
            Detection expected = createDetection(158, 241, 413, 100, "45");
            assertEquals(expected, actual);
        }

        {
            Detection input = createDetection(300, 100, 400, 100, "225");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "100%", "0", 500, 500, input);
            Detection expected = createDetection(449, -49, 707, 100, "225");
            assertEquals(expected, actual);
        }

        {
            Detection input = createDetection(300, 100, 400, 100, "225");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "50%", "0", 500, 500, input);
            Detection expected = createDetection(441, -41, 696, 100, "225");
            assertEquals(expected, actual);
        }
    }


    @Test
    public void testRotationWithoutClippingNonOrthogonal() {

        Detection input = createDetection(96, 140, 190, 42, "18.74");
        {
            Detection expected = createDetection(6, 170, 380, 42, "18.74");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "50%", "0", 1000, 1000, input);
            assertEquals(expected, actual);
        }

        {
            Detection expected = createDetection(89, 120, 190, 84, "18.74");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "0%", "50%", 1000, 1000, input);
            assertEquals(expected, actual);
        }

        {
            Detection expected = createDetection(0, 150, 380, 84, "18.74");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "50%", "50%", 1000, 1000, input);
            assertEquals(expected, actual);
        }

        {
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "0", "0", 500, 500, input);
            assertEquals(input, actual);
        }
    }


    @Test
    public void testRotationWithoutClippingOrthogonal() {

        Detection input = createDetection(50, 60, 20, 40, "90");
        {
            Detection expected = createDetection(26, 60, 20, 88, "90");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "0", "60%", 1000, 1000, input);
            assertEquals(expected, actual);
        }

        {
            Detection expected = createDetection(50, 58, 16, 40, "90");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "-10%", "0", 1000, 1000, input);
            assertEquals(expected, actual);
        }

        {
            Detection expected = createDetection(26, 58, 16, 88, "90");
            Detection actual = DetectionPaddingProcessor.padDetection(
                    "-10%", "60%", 1000, 1000, input);
            assertEquals(expected, actual);
        }
    }

    private static Detection createDetection(int x, int y, int width, int height, String rotation) {

        Map<String, String> detectionProperties = rotation == null
                ? Collections.emptyMap()
                : Collections.singletonMap("ROTATION", rotation);

        return new Detection(x, y, width, height, 1, 0, 0,
                detectionProperties);
    }


    private static void assertPadding(int x, int y, int width, int height, String xPadding, String yPadding,
                                      int frameWidth, int frameHeight,
                                      int expectedX, int expectedY, int expectedWidth, int expectedHeight) {
        assertPadding(x, y, width, height, xPadding, yPadding, frameWidth, frameHeight,
                expectedX, expectedY, expectedWidth, expectedHeight, false);
    }


    private static void assertShrinkToNothing(int x, int y, int width, int height, String xPadding, String yPadding,
                                              int frameWidth, int frameHeight,
                                              int expectedX, int expectedY, int expectedWidth, int expectedHeight) {
        assertPadding(x, y, width, height, xPadding, yPadding, frameWidth, frameHeight,
                expectedX, expectedY, expectedWidth, expectedHeight, true);
    }


    private static void assertPadding(int x, int y, int width, int height, String xPadding, String yPadding,
                                      int frameWidth, int frameHeight,
                                      int expectedX, int expectedY, int expectedWidth, int expectedHeight,
                                      boolean shrinkToNothing) {
        Detection detection = new Detection(x, y, width, height, -1, 0, 0, Collections.emptyMap());
        Detection expectedDetection = new Detection(expectedX, expectedY, expectedWidth, expectedHeight, -1, 0, 0,
                shrinkToNothing ? Collections.singletonMap("SHRUNK_TO_NOTHING", "TRUE") : Collections.emptyMap());
        Detection newDetection = DetectionPaddingProcessor.padDetection(xPadding, yPadding, frameWidth, frameHeight,
                detection);
        assertEquals(expectedDetection, newDetection);
    }


    @Test
    public void testRemoveZeroSizeDetectionsNoZeroDetections() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(50, 60, 20, 40, 1, 1, 1, Collections.emptyMap());
        Detection detection2 = new Detection(50, 60, 20, 40, 1, 2, 2, Collections.emptyMap());
        Detection detection3 = new Detection(50, 60, 20, 40, 1, 3, 3, Collections.emptyMap());
        detections.add(detection1);
        detections.add(detection2);
        detections.add(detection3);

        Track track1 = new Track(1, 2, 1, 1, 1, 3,
                1, 3, "FACE", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, false);

        assertEquals(1, filteredTracks.size());
        Track filteredTrack = filteredTracks.iterator().next();
        SortedSet<Detection> filteredDetections = filteredTrack.getDetections();
        assertEquals(3, filteredDetections.size());
        assertEquals(1, filteredTrack.getStartOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getEndOffsetFrameInclusive());
        assertEquals(1, filteredTrack.getStartOffsetTimeInclusive());
        assertEquals(3, filteredTrack.getEndOffsetTimeInclusive());
    }

    @Test
    public void testRemoveZeroSizeDetectionsOneZeroDetection() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(50, 60, 20, 40, 1, 1, 1, Collections.emptyMap());
        Detection detection2 = new Detection(50, 60, 0, 0, 1, 2, 2, Collections.emptyMap());
        Detection detection3 = new Detection(50, 60, 20, 40, 1, 3, 3, Collections.emptyMap());
        detections.add(detection1);
        detections.add(detection2);
        detections.add(detection3);

        Track track1 = new Track(1, 2, 1, 1, 1, 3,
                1, 3, "FACE", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, true);
        assertEquals(1, filteredTracks.size());
        Track filteredTrack = filteredTracks.iterator().next();
        SortedSet<Detection> filteredDetections = filteredTrack.getDetections();
        assertEquals(2, filteredDetections.size());
        assertEquals(1, filteredTrack.getStartOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getEndOffsetFrameInclusive());
        assertEquals(1, filteredTrack.getStartOffsetTimeInclusive());
        assertEquals(3, filteredTrack.getEndOffsetTimeInclusive());
    }

    @Test
    public void testRemoveOutsideFrameDetectionsOneDetection() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(-25, 60, 20, 40, 1, 1, 1, Collections.emptyMap());
        Detection detection2 = new Detection(50, 60, 20, 40, 1, 2, 2, Collections.emptyMap());
        Detection detection3 = new Detection(50, 60, 20, 40, 1, 3, 3, Collections.emptyMap());
        detections.add(detection1);
        detections.add(detection2);
        detections.add(detection3);

        Track track1 = new Track(1, 2, 1, 1, 1, 3,
                1, 3, "FACE", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, true);
        assertEquals(1, filteredTracks.size());
        Track filteredTrack = filteredTracks.iterator().next();
        SortedSet<Detection> filteredDetections = filteredTrack.getDetections();
        assertEquals(2, filteredDetections.size());
        assertEquals(2, filteredTrack.getStartOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getEndOffsetFrameInclusive());
        assertEquals(2, filteredTrack.getStartOffsetTimeInclusive());
        assertEquals(3, filteredTrack.getEndOffsetTimeInclusive());
    }

    @Test
    public void testDontRemoveDetectionPartiallyInFrame() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(-10, 60, 20, 40, 1, 1, 1, Collections.emptyMap());
        Detection detection2 = new Detection(50, 60, 20, 40, 1, 2, 2, Collections.emptyMap());
        Detection detection3 = new Detection(50, 60, 20, 40, 1, 3, 3, Collections.emptyMap());
        detections.add(detection1);
        detections.add(detection2);
        detections.add(detection3);

        Track track1 = new Track(1, 2, 1, 1, 1, 3,
                1, 3, "FACE", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, false);
        assertEquals(1, filteredTracks.size());
        Track filteredTrack = filteredTracks.iterator().next();
        SortedSet<Detection> filteredDetections = filteredTrack.getDetections();
        assertEquals(3, filteredDetections.size());
        assertEquals(1, filteredTrack.getStartOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getEndOffsetFrameInclusive());
        assertEquals(1, filteredTrack.getStartOffsetTimeInclusive());
        assertEquals(3, filteredTrack.getEndOffsetTimeInclusive());
    }

    @Test
    public void testRemoveIllFormedDetectionsOneOfEach() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(-25, 60, 20, 40, 1, 1, 1, Collections.emptyMap());
        Detection detection2 = new Detection(50, 60, 0, 0, 1, 2, 2, Collections.emptyMap());
        Detection detection3 = new Detection(50, 60, 20, 40, 1, 3, 3, Collections.emptyMap());
        detections.add(detection1);
        detections.add(detection2);
        detections.add(detection3);

        Track track1 = new Track(1, 2, 1, 1, 1, 3,
                1, 3, "FACE", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, true);
        assertEquals(1, filteredTracks.size());
        Track filteredTrack = filteredTracks.iterator().next();
        SortedSet<Detection> filteredDetections = filteredTrack.getDetections();
        assertEquals(1, filteredDetections.size());
        assertEquals(3, filteredTrack.getStartOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getEndOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getStartOffsetTimeInclusive());
        assertEquals(3, filteredTrack.getEndOffsetTimeInclusive());
    }

    @Test
    public void testDontRemoveDetectionsFromExemptTypes() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(-25, 60, 20, 40, 1, 1, 1, Collections.emptyMap());
        Detection detection2 = new Detection(50, 60, 0, 0, 1, 2, 2, Collections.emptyMap());
        Detection detection3 = new Detection(50, 60, 20, 40, 1, 3, 3, Collections.emptyMap());
        detections.add(detection1);
        detections.add(detection2);
        detections.add(detection3);

        Track track1 = new Track(1, 2, 1, 1, 1, 3,
                1, 3, "SPEECH", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, true);
        assertEquals(1, filteredTracks.size());
        Track filteredTrack = filteredTracks.iterator().next();
        SortedSet<Detection> filteredDetections = filteredTrack.getDetections();
        assertEquals(1, filteredDetections.size());
        assertEquals(3, filteredTrack.getStartOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getEndOffsetFrameInclusive());
        assertEquals(3, filteredTrack.getStartOffsetTimeInclusive());
        assertEquals(3, filteredTrack.getEndOffsetTimeInclusive());
    }


    @Test
    public void testRemoveZeroSizeDetectionsSingleDetectionTrack() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(50, 60, 0, 0, 1, 1, 1, Collections.emptyMap());
        detections.add(detection1);

        Track track1 = new Track(1, 2, 1, 1, 1, 1,
                1, 1, "FACE", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, true);

        assertTrue(filteredTracks.isEmpty());
    }

    @Test
    public void testRemoveZeroSizeDetectionsOneZeroDetectionAtEnd() {
        SortedSet<Detection> detections = new TreeSet<>();
        Detection detection1 = new Detection(50, 60, 20, 40, 1, 1, 1, Collections.emptyMap());
        Detection detection2 = new Detection(50, 60, 20, 40, 1, 2, 2, Collections.emptyMap());
        Detection detection3 = new Detection(50, 60, 0, 0, 1, 3, 3, Collections.emptyMap());
        detections.add(detection1);
        detections.add(detection2);
        detections.add(detection3);

        Track track1 = new Track(1, 2, 1, 1, 1, 3,
                1, 3, "FACE", 1, detections, Collections.emptyMap());

        SortedSet<Track> tracks = new TreeSet<>();
        tracks.add(track1);

        Collection<Track> filteredTracks = runRemoveIllFormedDetections(tracks, 200, 400, true);

        assertEquals(1, filteredTracks.size());
        Track filteredTrack = filteredTracks.iterator().next();
        SortedSet<Detection> filteredDetections = filteredTrack.getDetections();
        assertEquals(2, filteredDetections.size());
        assertEquals(1, filteredTrack.getStartOffsetFrameInclusive());
        assertEquals(2, filteredTrack.getEndOffsetFrameInclusive());
        assertEquals(1, filteredTrack.getStartOffsetTimeInclusive());
        assertEquals(2, filteredTrack.getEndOffsetTimeInclusive());
    }

    private Collection<Track> runRemoveIllFormedDetections(SortedSet<Track> tracks, int frameWidth, int frameHeight,
                                                           boolean checkSetTracks) {
        JsonUtils _jsonUtils = new JsonUtils(ObjectMapperFactory.customObjectMapper());
        InProgressBatchJobsService _mockInProgressJobs = mock(InProgressBatchJobsService.class);
        AggregateJobPropertiesUtil _mockAggregateJobPropertiesUtil = mock(AggregateJobPropertiesUtil.class);
        DetectionPaddingProcessor _detectionPaddingProcessor = new DetectionPaddingProcessor(
                _jsonUtils,
                _mockInProgressJobs,
                _mockAggregateJobPropertiesUtil);
        long jobId = 123;
        long mediaId = 5321;

        ArgumentCaptor<Collection<Track>> captor = ArgumentCaptor.forClass(Collection.class);
        Collection<Track> new_tracks = _detectionPaddingProcessor.removeIllFormedDetections(jobId, mediaId,
                0, 0, frameWidth, frameHeight, tracks);
        if (checkSetTracks) {
            verify(_mockInProgressJobs, atLeast(1))
                    .addWarning(eq(jobId), eq(mediaId), eq(IssueCodes.PADDING), anyString());
            verify(_mockInProgressJobs, atMost(2))
                    .addWarning(eq(jobId), eq(mediaId), eq(IssueCodes.PADDING), anyString());
            verify(_mockInProgressJobs)
                    .setTracks(eq(jobId), eq(mediaId), eq(0), eq(0), captor.capture());
            assertTrue(isEqualCollection(new_tracks, captor.getValue()));
        }
        else {
            verify(_mockInProgressJobs, times(0))
                    .addWarning(eq(jobId), eq(mediaId), eq(IssueCodes.PADDING), anyString());
            verify(_mockInProgressJobs, times(0))
                    .setTracks(eq(jobId), eq(mediaId), eq(0), eq(0), captor.capture());
        }
        return new_tracks;
    }
}

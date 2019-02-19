/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2018 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2018 The MITRE Corporation                                       *
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


package org.mitre.mpf.wfm.data;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mitre.mpf.wfm.data.entities.transients.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@ContextConfiguration(locations = "classpath:applicationContext.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class TestRedis {

    @Autowired
    private Redis _redis;

    @Autowired
    private RedisTemplate<String, Object> _redisTemplate;

    private static final long TEST_JOB_ID = 43532;

    private static final long TEST_MEDIA_ID = 623;

    private static ImmutableSortedSet<Track> _currentTracks;

    private static Track _differentStageTrack;

    private static Track _differentJobTrack;

    @BeforeClass
    public static void initClass() {
        Track t1 = new Track(
                TEST_JOB_ID,
                TEST_MEDIA_ID,
                0,
                0,
                100,
                123,
                4321,
                5423,
                "type1",
                0.5f,
                createDetections(),
                ImmutableSortedMap.of("a", "b", "c", "d"));
        Track t2 = new Track(
                TEST_JOB_ID,
                TEST_MEDIA_ID,
                0,
                0,
                110,
                133,
                4341,
                5433,
                "type2",
                0.6f,
                createDetections(),
                Collections.emptyMap());
        _currentTracks = ImmutableSortedSet.of(t1, t2);

        _differentStageTrack = new Track(
                TEST_JOB_ID,
                TEST_MEDIA_ID,
                1,
                0,
                110,
                133,
                4341,
                5433,
                "type3",
                0.6f,
                createDetections(),
                ImmutableSortedMap.of("e", "f"));

        _differentJobTrack = new Track(
                TEST_JOB_ID + 1,
                TEST_MEDIA_ID,
                0,
                0,
                110,
                133,
                4341,
                5433,
                "type2",
                0.6f,
                createDetections(),
                Collections.emptyMap());
    }


    private static List<Detection> createDetections() {
        Detection detection1 = new Detection(1, 2, 3, 4, 0,5, 6,
                                            Collections.emptyMap());

        Detection detection2 = new Detection(7, 8, 9, 10, 11, 12, 13,
                                             ImmutableSortedMap.of("prop1", "val1", "prop2", "val2"));

        return Arrays.asList(detection1, detection2);
    }


    @Before
    @After
    public void flushRedis() {
        _redisTemplate.execute((RedisCallback<Void>) redisConnection -> {
            redisConnection.flushAll();
            return null;
        });
    }

    private void addAllTestTracksToRedis() {
        for (Track track : _currentTracks) {
            _redis.addTrack(track);
        }
        _redis.addTrack(_differentJobTrack);
        _redis.addTrack(_differentStageTrack);
    }


    @Test
    public void canAddAndReplaceTracks() {
        addAllTestTracksToRedis();
        SortedSet<Track> retrievedTracks = _redis.getTracks(TEST_JOB_ID, TEST_MEDIA_ID, 0, 0);

        assertEquals(_currentTracks, retrievedTracks);

        SortedSet<Track> retrievedOtherStageTracks = _redis.getTracks(TEST_JOB_ID, TEST_MEDIA_ID, 1, 0);
        assertEquals(Collections.singleton(_differentStageTrack), retrievedOtherStageTracks);

        assertTrue(_redis.getTracks(1, TEST_MEDIA_ID, 0, 0).isEmpty());


        Track replacementTrack = new Track(
                TEST_JOB_ID,
                TEST_MEDIA_ID,
                0,
                0,
                101,
                124,
                4322,
                5424,
                "replacement",
                0.6f,
                createDetections(),
                ImmutableSortedMap.of("a", "b", "c", "d", "e", "f"));
        _redis.setTracks(TEST_JOB_ID, TEST_MEDIA_ID, 0, 0,
                         Collections.singletonList(replacementTrack));

        SortedSet<Track> replacementTracks = _redis.getTracks(TEST_JOB_ID, TEST_MEDIA_ID, 0, 0);
        assertEquals(1, replacementTracks.size());
        assertTrue(replacementTracks.contains(replacementTrack));

        for (Track track : _currentTracks) {
            assertNotInRedis(track);
        }
    }


    @Test
    public void canHandleMissingTrackList() {
        SortedSet<Track> retrievedTracks = _redis.getTracks(TEST_JOB_ID, TEST_MEDIA_ID, 0, 0);
        assertTrue(retrievedTracks.isEmpty());
    }


    @Test
    public void canClearTracks() {
        addAllTestTracksToRedis();

        TransientMedia media = mock(TransientMedia.class);
        when(media.getId())
                .thenReturn(TEST_MEDIA_ID);

        TransientStage stage1 = mock(TransientStage.class, RETURNS_DEEP_STUBS);
        when(stage1.getActions().size())
                .thenReturn(1);

        TransientStage stage2 = mock(TransientStage.class, RETURNS_DEEP_STUBS);
        when(stage2.getActions().size())
                .thenReturn(1);


        TransientJob job = mock(TransientJob.class, RETURNS_DEEP_STUBS);
        when(job.getId())
                .thenReturn(TEST_JOB_ID);
        when(job.getPipeline().getStages())
                .thenReturn(ImmutableList.of(stage1, stage2));
        when(job.getMedia())
                .thenAnswer(invocation -> ImmutableList.of(media));


        _redis.clearTracks(job);

        Set<String> keys = _redisTemplate.keys("*");
        assertEquals(1, keys.size());
        SortedSet<Track> otherJobTracks = _redis.getTracks(_differentJobTrack.getJobId(),
                                                           _differentJobTrack.getMediaId(),
                                                           _differentJobTrack.getStageIndex(),
                                                           _differentJobTrack.getActionIndex());
        assertEquals(Collections.singleton(_differentJobTrack), otherJobTracks);


        for (Track track : _currentTracks) {
            assertNotInRedis(track);
        }
        assertNotInRedis(_differentStageTrack);
    }


    private void assertNotInRedis(Track track) {
        SortedSet<Track> tracks = _redis.getTracks(track.getJobId(),
                                                   track.getMediaId(),
                                                   track.getStageIndex(),
                                                   track.getActionIndex());
        assertFalse(tracks.contains(track));
    }
}
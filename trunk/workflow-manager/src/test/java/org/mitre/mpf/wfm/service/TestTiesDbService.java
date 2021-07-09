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


package org.mitre.mpf.wfm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHttpResponse;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.mpf.interop.util.TimeUtils;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.Algorithm;
import org.mitre.mpf.rest.api.pipelines.Pipeline;
import org.mitre.mpf.rest.api.pipelines.Task;
import org.mitre.mpf.wfm.data.entities.persistent.*;
import org.mitre.mpf.wfm.data.entities.transients.TrackCounter;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.util.*;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestTiesDbService {

    @Mock
    private PropertiesUtil _mockPropertiesUtil;

    @Mock
    private AggregateJobPropertiesUtil _mockAggregateJobPropertiesUtil;

    private final ObjectMapper _objectMapper = ObjectMapperFactory.customObjectMapper();

    @Mock
    private CallbackUtils _mockCallbackUtils;

    private TiesDbService _tiesDbService;


    @BeforeClass
    public static void initClass() {
        ThreadUtil.start();
    }

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        _tiesDbService = new TiesDbService(_mockPropertiesUtil, _mockAggregateJobPropertiesUtil,
                                           _objectMapper, _mockCallbackUtils);

        when(_mockPropertiesUtil.getSemanticVersion())
                .thenReturn("1.5");

        when(_mockPropertiesUtil.getHttpCallbackTimeoutMs())
                .thenReturn(200);

        when(_mockPropertiesUtil.getHttpCallbackRetryCount())
                .thenReturn(3);
    }


    @Test
    public void testAddAssertions() {
        var media1 = new MediaImpl(321, "file:///media1", null, null, Map.of(), Map.of(), null);
        media1.setSha256("MEDIA1_SHA");

        var media2 = new MediaImpl(325, "file:///media2", null, null, Map.of(), Map.of(), null);
        media2.setSha256("MEDIA2_SHA");

        var algo1 = new Algorithm("ALGO1", null, null, null, null, true, false);
        var algo2 = new Algorithm("ALGO2", null, null, null, null, true, false);

        var action1 = new Action("ACTION1", null, algo1.getName(), List.of());
        var action2 = new Action("ACTION2", null, algo2.getName(), List.of());

        var task1 = new Task("TASK1", null, List.of(action1.getName()));
        var task2 = new Task("TASK2", null, List.of(action2.getName()));

        var pipeline = new Pipeline("PIPELINE", null, List.of(task1.getName(), task2.getName()));
        var pipelineElements = new JobPipelineElements(
                pipeline, List.of(task1, task2), List.of(action1, action2), List.of(algo1, algo2));

        var job = new BatchJobImpl(
                123, null, null, pipelineElements, 4,
                true, null, null, List.of(media1, media2),
                Map.of(), Map.of());


        String url1 = "http://localhost:81/qwer";
        when(_mockAggregateJobPropertiesUtil.getValue(
                eq("TIES_DB_URL"),
                argThat(jp -> jp.getJob().equals(job)
                        && jp.getAction().equals(action1)
                        && (jp.getMedia().equals(media1) || jp.getMedia().equals(media2)))
        )).thenReturn(url1);

        String url2 = "http://localhost:90/qwer";
        when(_mockAggregateJobPropertiesUtil.getValue(
                eq("TIES_DB_URL"),
                argThat(jp -> jp.getJob().equals(job) && jp.getMedia().equals(media1)
                        && jp.getAction().equals(action2))
        )).thenReturn(url2);

        when(_mockCallbackUtils.executeRequest(any(HttpPost.class), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(
                        new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")));

        var trackCounter = new TrackCounter();
        trackCounter.set(media1.getId(), 0, 0, "FACE", 100);
        trackCounter.set(media1.getId(), 1, 0, "CLASS", 200);

        trackCounter.set(media2.getId(), 0, 0, "FACE", 300);
        trackCounter.set(media2.getId(), 1, 0, "CLASS", 400);

        var timeCompleted = Instant.ofEpochSecond(1622724824);
        var outputObjectLocation = URI.create("http://localhost:321/asdf");
        var outputSha = "ed2e2a154b4bf6802c3f418a64488b7bf3f734fa9ebfd568cf302ae4e8f4c3bb";

        var result = _tiesDbService.addAssertions(
                job,
                BatchJobStatusType.COMPLETE_WITH_WARNINGS,
                timeCompleted,
                outputObjectLocation,
                outputSha,
                trackCounter);

        result.join();

        // action1 on media1, action1 on media2, and action2 on media1 are configured TiesDb.
        // action2 on media2 is not configured to use TiesDb
        verify(_mockCallbackUtils, times(3))
                .executeRequest(any(), anyInt());

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE_WITH_WARNINGS,
                                media1,
                                url1,
                                algo1.getName(),
                                "FACE",
                                100,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE_WITH_WARNINGS,
                                media1,
                                url2,
                                algo2.getName(),
                                "CLASS",
                                200,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE_WITH_WARNINGS,
                                media2,
                                url1,
                                algo1.getName(),
                                "FACE",
                                300,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));
    }


    @Test
    public void testTaskMerging() {
        testTwoStageTrackMerging("TYPE1", 451, 452);
    }

    @Test
    public void testTaskMergingLastTaskNoTracks() {
        testTwoStageTrackMerging("NO TRACKS", 451, 0);
    }

    @Test
    public void testTaskMergingFirstTaskNoTracks() {
        testTwoStageTrackMerging("TYPE1", 0, 452);
    }

    @Test
    public void testTaskMergingNoTracks() {
        testTwoStageTrackMerging("NO TRACKS", 0, 0);
    }


    private void testTwoStageTrackMerging(String expectedType,
                                          int task1TrackCount, int task2TrackCount) {
        var job = createTwoStageTestJob();
        String url = "http://localhost:81/qwer";
        when(_mockAggregateJobPropertiesUtil.getValue(eq("TIES_DB_URL"), any(JobPart.class)))
                .thenReturn(url);

        when(_mockAggregateJobPropertiesUtil.getTasksToMerge(any(), any()))
                .thenReturn(Set.of(1));

        when(_mockCallbackUtils.executeRequest(any(HttpPost.class), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(
                        new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")));

        var trackCounter = new TrackCounter();
        trackCounter.set(321, 0, 0, "TYPE1", task1TrackCount);
        trackCounter.set(321, 1, 0, "TYPE2", task2TrackCount);

        var timeCompleted = Instant.ofEpochSecond(1622724824);
        var outputObjectLocation = URI.create("http://localhost:321/asdf");
        var outputSha = "ed2e2a154b4bf6802c3f418a64488b7bf3f734fa9ebfd568cf302ae4e8f4c3bb";

        var result = _tiesDbService.addAssertions(
                job,
                BatchJobStatusType.COMPLETE_WITH_WARNINGS,
                timeCompleted,
                outputObjectLocation,
                outputSha,
                trackCounter);

        result.join();

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE_WITH_WARNINGS,
                                job.getMedia().iterator().next(),
                                url,
                                "ALGO1",
                                expectedType,
                                task2TrackCount,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));

        verifyNoMoreInteractions(_mockCallbackUtils);
    }


    @Test
    public void testThreeStageMergeLast() {
        runThreeStageMergeLastTest(true);
    }

    @Test
    public void testThreeStageMergeLastAndOutputLastOnly() {
        when(_mockAggregateJobPropertiesUtil.isOutputLastTaskOnly(any(), any()))
                .thenReturn(true);
        runThreeStageMergeLastTest(false);
    }


    private void runThreeStageMergeLastTest(boolean verifyAlgo1Request) {
        var job = createThreeStageTestJob();
        String url = "http://localhost:81/qwer";

        when(_mockAggregateJobPropertiesUtil.getValue(eq("TIES_DB_URL"), any(JobPart.class)))
                .thenReturn(url);

        when(_mockAggregateJobPropertiesUtil.getTasksToMerge(any(), any()))
                .thenReturn(Set.of(2));

        when(_mockCallbackUtils.executeRequest(any(HttpPost.class), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(
                        new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")));

        var trackCounter = new TrackCounter();
        trackCounter.set(321, 0, 0, "TYPE1", 451);
        trackCounter.set(321, 1, 0, "TYPE2", 452);
        trackCounter.set(321, 2, 0, "TYPE3", 453);

        var timeCompleted = Instant.ofEpochSecond(1622724824);
        var outputObjectLocation = URI.create("http://localhost:321/asdf");
        var outputSha = "ed2e2a154b4bf6802c3f418a64488b7bf3f734fa9ebfd568cf302ae4e8f4c3bb";

        var result = _tiesDbService.addAssertions(
                job,
                BatchJobStatusType.COMPLETE,
                timeCompleted,
                outputObjectLocation,
                outputSha,
                trackCounter);

        result.join();

        if (verifyAlgo1Request) {
            verify(_mockCallbackUtils)
                    .executeRequest(
                            argThat(req -> httpRequestMatcher(
                                    job.getId(),
                                    BatchJobStatusType.COMPLETE,
                                    job.getMedia().iterator().next(),
                                    url,
                                    "ALGO1",
                                    "TYPE1",
                                    451,
                                    outputObjectLocation,
                                    outputSha,
                                    timeCompleted,
                                    req)),
                            eq(3));
        }

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE,
                                job.getMedia().iterator().next(),
                                url,
                                "ALGO2",
                                "TYPE2",
                                453,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));

        verifyNoMoreInteractions(_mockCallbackUtils);
    }


    @Test
    public void testMergingMiddleTask() {
        runMergeMiddleTest(true);
    }


    @Test
    public void testMergingMiddleTaskWithOutputLastTaskOnly() {
        when(_mockAggregateJobPropertiesUtil.isOutputLastTaskOnly(any(), any()))
                .thenReturn(true);
        runMergeMiddleTest(false);
    }


    private void runMergeMiddleTest(boolean verifyAlgo1Request) {
        var job = createThreeStageTestJob();
        String url = "http://localhost:81/qwer";

        when(_mockAggregateJobPropertiesUtil.getValue(eq("TIES_DB_URL"), any(JobPart.class)))
                .thenReturn(url);

        when(_mockAggregateJobPropertiesUtil.getTasksToMerge(any(), any()))
                .thenReturn(Set.of(1));

        when(_mockCallbackUtils.executeRequest(any(HttpPost.class), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(
                        new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")));

        var trackCounter = new TrackCounter();
        trackCounter.set(321, 0, 0, "TYPE1", 451);
        trackCounter.set(321, 1, 0, "TYPE2", 452);
        trackCounter.set(321, 2, 0, "TYPE3", 453);

        var timeCompleted = Instant.ofEpochSecond(1622724824);
        var outputObjectLocation = URI.create("http://localhost:321/asdf");
        var outputSha = "ed2e2a154b4bf6802c3f418a64488b7bf3f734fa9ebfd568cf302ae4e8f4c3bb";

        var result = _tiesDbService.addAssertions(
                job,
                BatchJobStatusType.COMPLETE,
                timeCompleted,
                outputObjectLocation,
                outputSha,
                trackCounter);

        result.join();

        if (verifyAlgo1Request) {
            verify(_mockCallbackUtils)
                    .executeRequest(
                            argThat(req -> httpRequestMatcher(
                                    job.getId(),
                                    BatchJobStatusType.COMPLETE,
                                    job.getMedia().iterator().next(),
                                    url,
                                    "ALGO1",
                                    "TYPE1",
                                    452,
                                    outputObjectLocation,
                                    outputSha,
                                    timeCompleted,
                                    req)),
                            eq(3));
        }

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE,
                                job.getMedia().iterator().next(),
                                url,
                                "ALGO3",
                                "TYPE3",
                                453,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));

        verifyNoMoreInteractions(_mockCallbackUtils);
    }


    @Test
    public void testMergingLastTwoTasks() {
        runMergeLastTwoTest();
    }

    @Test
    public void testMergingLastTwoTasksWithOutputLastTaskOnly() {
        when(_mockAggregateJobPropertiesUtil.isOutputLastTaskOnly(any(), any()))
                .thenReturn(true);
        runMergeLastTwoTest();
    }

    private void runMergeLastTwoTest() {
        var job = createThreeStageTestJob();
        String url = "http://localhost:81/qwer";

        when(_mockAggregateJobPropertiesUtil.getValue(eq("TIES_DB_URL"), any(JobPart.class)))
                .thenReturn(url);

        when(_mockAggregateJobPropertiesUtil.getTasksToMerge(any(), any()))
                .thenReturn(Set.of(1, 2));

        when(_mockCallbackUtils.executeRequest(any(HttpPost.class), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(
                        new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")));

        var trackCounter = new TrackCounter();
        trackCounter.set(321, 0, 0, "TYPE1", 451);
        trackCounter.set(321, 1, 0, "TYPE2", 452);
        trackCounter.set(321, 2, 0, "TYPE3", 453);

        var timeCompleted = Instant.ofEpochSecond(1622724824);
        var outputObjectLocation = URI.create("http://localhost:321/asdf");
        var outputSha = "ed2e2a154b4bf6802c3f418a64488b7bf3f734fa9ebfd568cf302ae4e8f4c3bb";

        var result = _tiesDbService.addAssertions(
                job,
                BatchJobStatusType.COMPLETE,
                timeCompleted,
                outputObjectLocation,
                outputSha,
                trackCounter);

        result.join();

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE,
                                job.getMedia().iterator().next(),
                                url,
                                "ALGO1",
                                "TYPE1",
                                453,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));
        verifyNoMoreInteractions(_mockCallbackUtils);
    }


    @Test
    public void canHandleInvalidUri() {
        var job = createTwoStageTestJob();
        var media = job.getMedia().iterator().next();
        var badAction = job.getPipelineElements().getAction(0, 0);
        var goodAction = job.getPipelineElements().getAction(1, 0);

        when(_mockAggregateJobPropertiesUtil.getValue(
                eq("TIES_DB_URL"),
                argThat(jp -> jp.getJob().equals(job) && jp.getMedia().equals(media)
                        && jp.getAction().equals(badAction)))
        ).thenReturn("BAD URI");

        String url = "http://localhost:81/qwer";
        when(_mockAggregateJobPropertiesUtil.getValue(
                eq("TIES_DB_URL"),
                argThat(jp -> jp.getJob().equals(job) && jp.getMedia().equals(media)
                        && jp.getAction().equals(goodAction)))
        ).thenReturn(url);

        when(_mockCallbackUtils.executeRequest(any(HttpPost.class), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(
                        new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")));

        var trackCounter = new TrackCounter();
        trackCounter.set(media.getId(), 0, 0, "TEXT", 24);
        trackCounter.set(media.getId(), 1, 0, "KEYWORD", 27);

        var timeCompleted = Instant.ofEpochSecond(1622724824);
        var outputObjectLocation = URI.create("http://localhost:321/asdf");
        var outputSha = "ed2e2a154b4bf6802c3f418a64488b7bf3f734fa9ebfd568cf302ae4e8f4c3bb";

        var result = _tiesDbService.addAssertions(
                job,
                BatchJobStatusType.COMPLETE,
                timeCompleted,
                outputObjectLocation,
                outputSha,
                trackCounter);

        // TiesDbService logs a warning and always returns a successful future.
        result.join();

        verify(_mockCallbackUtils, times(1))
                .executeRequest(any(), anyInt());

        verify(_mockCallbackUtils)
                .executeRequest(
                        argThat(req -> httpRequestMatcher(
                                job.getId(),
                                BatchJobStatusType.COMPLETE,
                                media,
                                url,
                                goodAction.getAlgorithm(),
                                "KEYWORD",
                                27,
                                outputObjectLocation,
                                outputSha,
                                timeCompleted,
                                req)),
                        eq(3));
    }


    @Test
    public void canHandleHttpError() {
        var job = createTwoStageTestJob();
        var media = job.getMedia().iterator().next();

        String url = "http://localhost:81/qwer";
        when(_mockAggregateJobPropertiesUtil.getValue(
                eq("TIES_DB_URL"),
                argThat(jp -> jp.getJob().equals(job) && jp.getMedia().equals(media)))
        ).thenReturn(url);

        var trackCounter = new TrackCounter();
        trackCounter.set(media.getId(), 0, 0, "TEXT", 34);
        trackCounter.set(media.getId(), 1, 0, "KEYWORD", 37);

        var timeCompleted = Instant.ofEpochSecond(1622724824);
        var outputObjectLocation = URI.create("http://localhost:321/asdf");
        var outputSha = "ed2e2a154b4bf6802c3f418a64488b7bf3f734fa9ebfd568cf302ae4e8f4c3bb";

        ArgumentMatcher<HttpUriRequest> action1Matcher = request -> httpRequestMatcher(
                job.getId(),
                BatchJobStatusType.COMPLETE_WITH_ERRORS,
                media,
                url,
                job.getPipelineElements().getAlgorithm(0, 0).getName(),
                "TEXT",
                34,
                outputObjectLocation,
                outputSha,
                timeCompleted,
                request);

        ArgumentMatcher<HttpUriRequest> action2Matcher = request -> httpRequestMatcher(
                job.getId(),
                BatchJobStatusType.COMPLETE_WITH_ERRORS,
                media,
                url,
                job.getPipelineElements().getAlgorithm(1, 0).getName(),
                "KEYWORD",
                37,
                outputObjectLocation,
                outputSha,
                timeCompleted,
                request);

        when(_mockCallbackUtils.executeRequest(argThat(action1Matcher), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(
                        new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")));

        when(_mockCallbackUtils.executeRequest(argThat(action2Matcher), eq(3)))
                .thenReturn(ThreadUtil.completedFuture(createErrorResponse()));

        var result = _tiesDbService.addAssertions(
                job,
                BatchJobStatusType.COMPLETE_WITH_ERRORS,
                timeCompleted,
                outputObjectLocation,
                outputSha,
                trackCounter);

        // TiesDbService logs a warning and always returns a successful future.
        result.join();

        verify(_mockCallbackUtils, times(2))
                .executeRequest(any(), anyInt());
    }

    private static HttpResponse createErrorResponse() {
        var response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 400, "BAD REQUEST");
        var entity = new BasicHttpEntity();
        response.setEntity(entity);
        var bytes = "<error>".getBytes(StandardCharsets.UTF_8);
        entity.setContent(new ByteArrayInputStream(bytes));
        return response;
    }


    private static BatchJob createTwoStageTestJob() {
        var media = new MediaImpl(321, "file:///media1", null, null, Map.of(), Map.of(), null);
        media.setSha256("MEDIA1_SHA");

        var algo1 = new Algorithm("ALGO1", null, null, null, null, true, false);
        var algo2 = new Algorithm("ALGO2", null, null, null, null, true, false);

        var action1 = new Action("ACTION1", null, algo1.getName(), List.of());
        var action2 = new Action("ACTION2", null, algo2.getName(), List.of());

        var task1 = new Task("TASK1", null, List.of(action1.getName()));
        var task2 = new Task("TASK2", null, List.of(action2.getName()));

        var pipeline = new Pipeline("PIPELINE", null, List.of(task1.getName(), task2.getName()));
        var pipelineElements = new JobPipelineElements(
                pipeline, List.of(task1, task2), List.of(action1, action2), List.of(algo1, algo2));

        return new BatchJobImpl(
                123, null, null, pipelineElements, 4,
                true, null, null, List.of(media),
                Map.of(), Map.of());
    }


    private static BatchJob createThreeStageTestJob() {
        var media = new MediaImpl(321, "file:///media1", null, null, Map.of(), Map.of(), null);
        media.setSha256("MEDIA1_SHA");

        var algo1 = new Algorithm("ALGO1", null, null, null, null, true, false);
        var algo2 = new Algorithm("ALGO2", null, null, null, null, true, false);
        var algo3 = new Algorithm("ALGO3", null, null, null, null, true, false);

        var action1 = new Action("ACTION1", null, algo1.getName(), List.of());
        var action2 = new Action("ACTION2", null, algo2.getName(), List.of());
        var action3 = new Action("ACTION3", null, algo3.getName(), List.of());

        var task1 = new Task("TASK1", null, List.of(action1.getName()));
        var task2 = new Task("TASK2", null, List.of(action2.getName()));
        var task3 = new Task("TASK3", null, List.of(action3.getName()));

        var pipeline = new Pipeline("PIPELINE", null,
                                    List.of(task1.getName(), task2.getName(), task3.getName()));
        var pipelineElements = new JobPipelineElements(
                pipeline,
                List.of(task1, task2, task3),
                List.of(action1, action2, action3),
                List.of(algo1, algo2, algo3));

        return new BatchJobImpl(
                123, null, null, pipelineElements, 4,
                true, null, null, List.of(media),
                Map.of(), Map.of());
    }


    private boolean httpRequestMatcher(
            long jobId,
            BatchJobStatusType jobStatus,
            Media media,
            String tiesDbBaseUrl,
            String algorithm,
            String trackType,
            int trackCount,
            URI outputUri,
            String outputObjectSha,
            Instant timeCompleted,
            HttpUriRequest httpRequest) {
        if (!(httpRequest instanceof HttpPost)) {
            return false;
        }

        var post = (HttpPost) httpRequest;

        JsonNode assertion;
        try {
            assertion = _objectMapper.readTree(post.getEntity().getContent());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Assertions that apply to all possible arguments
        assertTrue(assertion.isObject());
        assertFalse(assertion.get("assertionId").textValue().isBlank());
        assertEquals("UNCLASSIFIED", assertion.get("securityTag").textValue());
        assertEquals("OpenMPF", assertion.get("system").textValue());

        var dataObject = assertion.get("dataObject");
        assertEquals("1.5", dataObject.get("systemVersion").textValue());
        assertFalse(dataObject.get("systemHostname").textValue().isBlank());
        assertEquals("PIPELINE", dataObject.get("pipeline").textValue());


        // Assertions specific to given arguments
        var requestUri = httpRequest.getURI().toString();
        var expectedUri = tiesDbBaseUrl + "/api/db/supplementals?sha256Hash=" + media.getSha256();
        if (!expectedUri.equals(requestUri)) {
            return false;
        }

        var expectedInfoType = "OpenMPF " + trackType;
        if (!expectedInfoType.equals(assertion.get("informationType").textValue())) {
            return false;
        }
        if (!algorithm.equals(dataObject.get("algorithm").textValue())) {
            return false;
        }
        if (!trackType.equals(dataObject.get("outputType").textValue())) {
            return false;
        }
        if (jobId != dataObject.get("jobId").longValue()) {
            return false;
        }
        if (!outputUri.toString().equals(dataObject.get("outputUri").textValue())) {
            return false;
        }
        if (!outputObjectSha.equals(dataObject.get("sha256OutputHash").textValue())) {
            return false;
        }
        if (!TimeUtils.toIsoString(timeCompleted)
                .equals(dataObject.get("processDate").textValue())) {
            return false;
        }
        if (!jobStatus.toString().equals(dataObject.get("jobStatus").textValue())) {
            return false;
        }
        if (trackCount != dataObject.get("trackCount").intValue()) {
            return false;
        }

        return true;
    }
}


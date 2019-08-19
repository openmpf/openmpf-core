package org.mitre.mpf.wfm.businessrules; /******************************************************************************
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


import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.mpf.rest.api.JobCreationMediaData;
import org.mitre.mpf.rest.api.JobCreationRequest;
import org.mitre.mpf.rest.api.pipelines.*;
import org.mitre.mpf.wfm.businessrules.impl.JobRequestServiceImpl;
import org.mitre.mpf.wfm.camel.routes.MediaRetrieverRouteBuilder;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.access.JobRequestDao;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.entities.persistent.*;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.enums.UriScheme;
import org.mitre.mpf.wfm.pipeline.PipelineService;
import org.mitre.mpf.wfm.service.JobStatusBroadcaster;
import org.mitre.mpf.wfm.util.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

public class TestJobRequestService {

    private final PropertiesUtil _mockPropertiesUtil = mock(PropertiesUtil.class);

    private final AggregateJobPropertiesUtil _aggregateJobPropertiesUtil
            = new AggregateJobPropertiesUtil(_mockPropertiesUtil, null, null);

    private final PipelineService _mockPipelineService = mock(PipelineService.class);

    private final JsonUtils _jsonUtils = new JsonUtils(ObjectMapperFactory.customObjectMapper());

    private final JmsUtils _mockJmsUtils = mock(JmsUtils.class);

    private final InProgressBatchJobsService _inProgressJobs = new InProgressBatchJobsService(_mockPropertiesUtil,
                                                                                              null);

    private final JobRequestDao _mockJobRequestDao = mock(JobRequestDao.class);

    private final MarkupResultDao _mockMarkupResultDao = mock(MarkupResultDao.class);

    private final JobStatusBroadcaster _mockJobStatusBroadcaster = mock(JobStatusBroadcaster.class);

    private final ProducerTemplate _mockProduceTemplate = mock(ProducerTemplate.class);


    private final JobRequestService _jobRequestService
            = new JobRequestServiceImpl(_mockPropertiesUtil, _aggregateJobPropertiesUtil, _mockPipelineService,
                                        _jsonUtils, _mockJmsUtils, _inProgressJobs, _mockJobRequestDao,
                                        _mockMarkupResultDao, _mockJobStatusBroadcaster, _mockProduceTemplate);

    @Rule
    public TemporaryFolder _temporaryFolder = new TemporaryFolder();

    @Before
    public void init() throws IOException {
        when(_mockPropertiesUtil.getTemporaryMediaDirectory())
                .thenReturn(_temporaryFolder.newFolder("temp-media"));
    }



    private static JobCreationRequest createTestJobCreationRequest() {
        var jobCreationMedia1 = new JobCreationMediaData("http://my_media1.mp4");
        jobCreationMedia1.getProperties().put("media_prop1", "media_val1");
        var jobCreationMedia2 = new JobCreationMediaData("http://my_media2.mp4");
        jobCreationMedia2.getProperties().put("media_prop2", "media_val2");

        var jobCreationRequest = new JobCreationRequest();
        jobCreationRequest.setMedia(List.of(jobCreationMedia1, jobCreationMedia2));
        jobCreationRequest.getJobProperties().put("job_prop1", "job_val1");
        jobCreationRequest.getAlgorithmProperties().put("TEST ALGO", Map.of("algo_prop1", "algo_val1"));
        jobCreationRequest.setExternalId("external_id");
        jobCreationRequest.setPipelineName("TEST PIPELINE");
        jobCreationRequest.setBuildOutput(true);
        jobCreationRequest.setCallbackMethod("GET");
        jobCreationRequest.setCallbackURL("http://callback");

        return jobCreationRequest;
    }


    private static JobPipelineComponents createJobPipelineComponents() {
        var algorithm = new Algorithm("TEST ALGO", "desc", ActionType.DETECTION,
                                      new Algorithm.Requires(List.of()),
                                      new Algorithm.Provides(List.of(), List.of()),
                                      true, true);
        var action = new Action("TEST ACTION", "descr", algorithm.getName(), List.of());
        var task = new Task("Test Task", "desc", List.of(action.getName()));
        var pipeline = new Pipeline("TEST PIPELINE", "desc", List.of(task.getName()));
        return new JobPipelineComponents(pipeline, List.of(task), List.of(action), List.of(algorithm));
    }


    @Test
    public void canCreateJob() {
        int defaultPriority = 8;
        when(_mockPropertiesUtil.getJmsPriority())
                .thenReturn(defaultPriority);

        var pipelineComponents = createJobPipelineComponents();
        when(_mockPipelineService.getBatchPipelineComponents("TEST PIPELINE"))
                .thenReturn(pipelineComponents);

        var mockSystemPropSnapshot = mock(SystemPropertiesSnapshot.class);
        when(_mockPropertiesUtil.createSystemPropertiesSnapshot())
                .thenReturn(mockSystemPropSnapshot);

        var returnedJobRequestEntity = new JobRequest() {
            @Override
            public long getId() {
                return 123;
            }
        };
        returnedJobRequestEntity.setStatus(BatchJobStatusType.INITIALIZED);
        returnedJobRequestEntity.setPipeline("TEST PIPELINE");
        returnedJobRequestEntity.setPriority(defaultPriority);

        when(_mockJobRequestDao.persist(
                argThat(jr -> jr.getId() == 0 && jr.getStatus() == BatchJobStatusType.INITIALIZED
                        && jr.getPipeline().equals("TEST PIPELINE") && jr.getPriority() == defaultPriority)))
                .thenReturn(returnedJobRequestEntity);

        when(_mockJobRequestDao.persist(returnedJobRequestEntity))
                .thenReturn(returnedJobRequestEntity);


        var jobCreationRequest = createTestJobCreationRequest();

        var jobRequestEntity = _jobRequestService.run(jobCreationRequest);


        InOrder jobStatusBroadcastOrder = inOrder(_mockJobStatusBroadcaster);
        jobStatusBroadcastOrder.verify(_mockJobStatusBroadcaster)
                .broadcast(123, 0, BatchJobStatusType.INITIALIZED);
        jobStatusBroadcastOrder.verify(_mockJobStatusBroadcaster)
                .broadcast(123, 0, BatchJobStatusType.IN_PROGRESS);

        assertEquals(123, jobRequestEntity.getId());
        assertEquals(BatchJobStatusType.IN_PROGRESS, jobRequestEntity.getStatus());

        verify(_mockProduceTemplate)
                .sendBodyAndHeaders(eq(MediaRetrieverRouteBuilder.ENTRY_POINT), eq(ExchangePattern.InOnly),
                                    isNull(), eq(Map.of(MpfHeaders.JOB_ID, 123L,
                                                        MpfHeaders.JMS_PRIORITY, defaultPriority)));


        BatchJob job = _inProgressJobs.getJob(123);
        assertArrayEquals(jobRequestEntity.getJob(), _jsonUtils.serialize(job));

        assertEquals(123, job.getId());
        assertEquals(jobCreationRequest.getExternalId(), job.getExternalId().get());
        assertEquals(0, job.getCurrentTaskIndex());
        assertSame(mockSystemPropSnapshot, job.getSystemPropertiesSnapshot());
        assertSame(pipelineComponents, job.getPipelineComponents());
        assertEquals(defaultPriority, job.getPriority());
        assertTrue(job.isOutputEnabled());
        assertEquals(jobCreationRequest.getCallbackURL(), job.getCallbackUrl().get());
        assertEquals(jobCreationRequest.getCallbackMethod(), job.getCallbackMethod().get());

        assertEquals(2, job.getMedia().size());
        Media media1 = job.getMedia()
                .stream()
                .filter(m -> m.getUri().equals("http://my_media1.mp4"))
                .findAny()
                .get();
        assertEquals("media_val1", media1.getMediaSpecificProperty("media_prop1"));

        Media media2 = job.getMedia()
                .stream()
                .filter(m -> m.getUri().equals("http://my_media2.mp4"))
                .findAny()
                .get();
        assertEquals("media_val2", media2.getMediaSpecificProperty("media_prop2"));

        assertEquals(jobCreationRequest.getJobProperties(), job.getJobProperties());
        assertEquals(jobCreationRequest.getAlgorithmProperties(), job.getOverriddenAlgorithmProperties());
    }


    @Test
    public void canResubmitJob() throws IOException {
        var originalJobPipelineComponents = createJobPipelineComponents();
        var originalJob = new BatchJobImpl(
                321,
                "external_id",
                new SystemPropertiesSnapshot(Map.of("my.property", "5")),
                originalJobPipelineComponents,
                3,
                true,
                "http://callback",
                "POST",
                List.of(new MediaImpl(567, "http://media.mp4", UriScheme.HTTP, Paths.get("temp"),
                                      Map.of("media_prop1", "media_val1"), "error")),
                Map.of("job_prop1", "job_val1"),
                Map.of("TEST ALGO" , Map.of("algo_prop1", "algo_val1")));
        originalJob.addDetectionProcessingError(
                new DetectionProcessingError(321, 1, 1, 1, 1, 1,
                                             "error"));
        originalJob.addWarning("warning");


        var existingJobRequestEntity = new JobRequest() {
            @Override
            public long getId() {
                return 321;
            }
        };

        existingJobRequestEntity.setStatus(BatchJobStatusType.COMPLETE);
        existingJobRequestEntity.setJob(_jsonUtils.serialize(originalJob));

        when(_mockJobRequestDao.findById(321))
                .thenReturn(existingJobRequestEntity);

        var newJobPipelineComponents = createJobPipelineComponents();
        when(_mockPipelineService.getBatchPipelineComponents(originalJobPipelineComponents.getName()))
                .thenReturn(newJobPipelineComponents);

        when(_mockPropertiesUtil.createSystemPropertiesSnapshot())
                .thenReturn(new SystemPropertiesSnapshot(Map.of("my.property", "10")));

        var newJobRequestEntity = new JobRequest() {
            @Override
            public long getId() {
                return 321;
            }
        };
        newJobRequestEntity.setPriority(3);
        when(_mockJobRequestDao.persist(argThat(
                jr -> jr.getId() == 321 && jr.getPriority() == 3 && jr.getStatus() == BatchJobStatusType.INITIALIZED
                    && jr.getPipeline().equals(existingJobRequestEntity.getPipeline())
                        && jr.getOutputObjectPath() == null && jr.getJob() == null)))
                .thenReturn(newJobRequestEntity);

        when(_mockJobRequestDao.persist(newJobRequestEntity))
                .thenReturn(newJobRequestEntity);

        File artifactsDir = _temporaryFolder.newFolder("artifacts");
        Files.writeString(artifactsDir.toPath().resolve("artifact.bin"), "hello world");
        when(_mockPropertiesUtil.getJobArtifactsDirectory(anyLong()))
                .thenReturn(artifactsDir);

        File outputObjectsDir = _temporaryFolder.newFolder("output_objects");
        Files.writeString(outputObjectsDir.toPath().resolve("outputobject.json"), "hello world");
        when(_mockPropertiesUtil.getJobOutputObjectsDirectory(anyLong()))
                .thenReturn(outputObjectsDir);

        File markupDir = _temporaryFolder.newFolder("markup");
        Files.writeString(markupDir.toPath().resolve("markup.bin"), "hello world");
        when(_mockPropertiesUtil.getJobMarkupDirectory(anyLong()))
                .thenReturn(markupDir);



        _jobRequestService.resubmit(321, -1);



        InOrder jobStatusBroadcastOrder = inOrder(_mockJobStatusBroadcaster);
        jobStatusBroadcastOrder.verify(_mockJobStatusBroadcaster)
                .broadcast(321, 0, BatchJobStatusType.INITIALIZED);
        jobStatusBroadcastOrder.verify(_mockJobStatusBroadcaster)
                .broadcast(321, 0, BatchJobStatusType.IN_PROGRESS);

        verify(_mockProduceTemplate)
                .sendBodyAndHeaders(eq(MediaRetrieverRouteBuilder.ENTRY_POINT), eq(ExchangePattern.InOnly),
                                    isNull(), eq(Map.of(MpfHeaders.JOB_ID, 321L,
                                                        MpfHeaders.JMS_PRIORITY, 3)));



        BatchJob newJob = _inProgressJobs.getJob(321);
        assertArrayEquals(newJobRequestEntity.getJob(), _jsonUtils.serialize(newJob));
        assertEquals(BatchJobStatusType.IN_PROGRESS, newJobRequestEntity.getStatus());

        assertEquals(BatchJobStatusType.IN_PROGRESS, newJob.getStatus());
        assertNotSame(newJob.getPipelineComponents(), originalJob.getPipelineComponents());
        assertEquals(newJob.getPipelineComponents().getName(), originalJob.getPipelineComponents().getName());
        assertEquals("10", newJob.getSystemPropertiesSnapshot().lookup("my.property"));
        assertEquals(0, newJob.getCurrentTaskIndex());
        assertEquals(newJob.getExternalId().get(), originalJob.getExternalId().get());
        assertEquals(newJob.getPriority(), originalJob.getPriority());
        assertEquals(newJob.isOutputEnabled(), originalJob.isOutputEnabled());
        assertEquals(newJob.getOverriddenAlgorithmProperties(), originalJob.getOverriddenAlgorithmProperties());
        assertEquals(newJob.getJobProperties(), originalJob.getJobProperties());
        assertEquals(newJob.getCallbackUrl().get(), originalJob.getCallbackUrl().get());
        assertEquals(newJob.getCallbackMethod().get(), originalJob.getCallbackMethod().get());
        assertTrue(newJob.getWarnings().isEmpty());
        assertTrue(newJob.getDetectionProcessingErrors().isEmpty());

        assertEquals(newJob.getMedia().size(), originalJob.getMedia().size());
        assertEquals(1, newJob.getMedia().size());

        Media newMedia = newJob.getMedia().asList().get(0);
        Media originalMedia = originalJob.getMedia().asList().get(0);
        assertEquals(newMedia.getUri(), originalMedia.getUri());
        assertEquals(newMedia.getMediaSpecificProperties(), originalMedia.getMediaSpecificProperties());
        assertNull(newMedia.getMessage());

        assertFalse(Files.exists(artifactsDir.toPath()));
        assertFalse(Files.exists(outputObjectsDir.toPath()));
        assertFalse(Files.exists(markupDir.toPath()));
    }



    @Test
    public void canCancel() throws Exception {
        long jobId = 5465;
        var jobRequestEntity = new JobRequest() {
            @Override
            public long getId() {
                return jobId;
            }
        };

        _inProgressJobs.addJob(
                jobId, null, new SystemPropertiesSnapshot(Map.of()), createJobPipelineComponents(),
                3, true, null, null,
                List.of(new MediaImpl(323, "http://example.mp4", UriScheme.HTTP, Path.of("temp"), Map.of(),
                                      null)),
                Map.of(), Map.of());

        jobRequestEntity.setStatus(BatchJobStatusType.IN_PROGRESS);

        when(_mockJobRequestDao.findById(jobId))
                .thenReturn(jobRequestEntity);


        _jobRequestService.cancel(jobId);

        assertTrue(_inProgressJobs.getJob(jobId).isCancelled());

        verify(_mockJmsUtils)
                .cancel(jobId);


        var persistedRequestCaptor = ArgumentCaptor.forClass(JobRequest.class);
        verify(_mockJobRequestDao)
                .persist(persistedRequestCaptor.capture());

        BatchJob job = _inProgressJobs.getJob(jobId);
        assertTrue(job.isCancelled());

        JobRequest persistedRequest = persistedRequestCaptor.getValue();
        assertEquals(BatchJobStatusType.CANCELLING, persistedRequest.getStatus());
        assertArrayEquals(persistedRequest.getJob(), _jsonUtils.serialize(job));
    }
}
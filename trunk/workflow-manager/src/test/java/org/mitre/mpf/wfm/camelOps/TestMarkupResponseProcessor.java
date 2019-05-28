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

package org.mitre.mpf.wfm.camelOps;

import org.apache.camel.Exchange;
import org.junit.Before;
import org.junit.Test;
import org.mitre.mpf.test.TestUtil;
import org.mitre.mpf.wfm.buffers.Markup;
import org.mitre.mpf.wfm.camel.operations.markup.MarkupResponseProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.transients.TransientJob;
import org.mitre.mpf.wfm.data.entities.transients.TransientMedia;
import org.mitre.mpf.wfm.data.entities.transients.TransientMediaImpl;
import org.mitre.mpf.wfm.data.entities.transients.TransientPipeline;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.MarkupStatus;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.enums.UriScheme;
import org.mitre.mpf.wfm.service.StorageService;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestMarkupResponseProcessor {

    @InjectMocks
    private MarkupResponseProcessor _markupResponseProcessor;

    @Mock
    private InProgressBatchJobsService _mockInProgressJobs;

    @Mock
    private MarkupResultDao _mockMarkupResultDao;

    @Mock
    private StorageService _mockStorageService;

    private static final long TEST_JOB_ID = 1236;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testMarkupResponse() {
        Markup.MarkupResponse.Builder responseBuilder = Markup.MarkupResponse.newBuilder()
                .setHasError(false)
                .clearErrorMessage();
        MarkupResult markupResult = runMarkupProcessor(responseBuilder);
        assertNull(markupResult.getMessage());
        assertEquals(MarkupStatus.COMPLETE, markupResult.getMarkupStatus());

        verify(_mockInProgressJobs, never())
                .setJobStatus(anyLong(), any());
    }


    @Test
    public void canHandleMarkupError() {
        String errorMessage = "errorMessage1";
        Markup.MarkupResponse.Builder responseBuilder = Markup.MarkupResponse.newBuilder()
                .setHasError(true)
                .setErrorMessage(errorMessage);

        MarkupResult markupResult = runMarkupProcessor(responseBuilder);
        assertEquals(errorMessage, markupResult.getMessage());
        assertEquals(MarkupStatus.FAILED, markupResult.getMarkupStatus());

        verify(_mockInProgressJobs)
                .setJobStatus(TEST_JOB_ID, BatchJobStatusType.IN_PROGRESS_ERRORS);
    }


    @Test
    public void canHandleMarkupWarning() {
        doAnswer(invocation -> {
            invocation.getArgumentAt(0)
                    .setMarkupStatus(MarkupStatus.COMPLETE_WITH_WARNING);
            return null;
        }).when(_mockStorageService).store(any(MarkupResult.class));

        Markup.MarkupResponse.Builder responseBuilder = Markup.MarkupResponse.newBuilder()
                .setHasError(false);

        MarkupResult markupResult = runMarkupProcessor(responseBuilder);
        assertEquals(MarkupStatus.COMPLETE_WITH_WARNING, markupResult.getMarkupStatus());

        verify(_mockInProgressJobs)
                .setJobStatus(TEST_JOB_ID, BatchJobStatusType.IN_PROGRESS_WARNINGS);
    }


    private MarkupResult runMarkupProcessor(Markup.MarkupResponse.Builder markupResponseBuilder) {
        long mediaId = 1532;
        int mediaIndex = 2;
        int taskIndex = 4;
        int actionIndex = 6;

        Markup.MarkupResponse markupResponse = markupResponseBuilder
                .setMediaId(mediaId)
                .setMediaIndex(mediaIndex)
                .setTaskIndex(taskIndex)
                .setActionIndex(actionIndex)
                .setRequestId(mediaId)
                .setOutputFileUri("output.txt")
                .build();


        TransientPipeline dummyPipeline = new TransientPipeline(
                "TEST_MARKUP_PIPELINE", "testMarkupPipelineDescription", Collections.emptyList());

        URI mediaUri = URI.create("file:///samples/meds1.jpg");
        TransientMedia media = new TransientMediaImpl(mediaId, mediaUri.toString(), UriScheme.get(mediaUri),
                                                      Paths.get(mediaUri), Collections.emptyMap(), null);
        TransientJob job = mock(TransientJob.class);
        when(job.getId())
                .thenReturn(TEST_JOB_ID);
        when(job.getPipeline())
                .thenReturn(dummyPipeline);
        when(job.getMedia(mediaId))
                .thenReturn(media);

        when(_mockInProgressJobs.containsJob(TEST_JOB_ID))
                .thenReturn(true);
        when(_mockInProgressJobs.getJob(TEST_JOB_ID))
                .thenReturn(job);

        Exchange exchange = TestUtil.createTestExchange();
        exchange.getIn().getHeaders().put(MpfHeaders.JOB_ID, TEST_JOB_ID);
        exchange.getIn().setBody(markupResponse);

        _markupResponseProcessor.process(exchange);

        ArgumentCaptor<MarkupResult> markupCaptor1 = ArgumentCaptor.forClass(MarkupResult.class);
        verify(_mockStorageService)
                .store(markupCaptor1.capture());

        ArgumentCaptor<MarkupResult> markupCaptor2 = ArgumentCaptor.forClass(MarkupResult.class);
        verify(_mockMarkupResultDao)
                .persist(markupCaptor2.capture());

        MarkupResult markupResult = markupCaptor2.getValue();
        assertSame(markupResult, markupCaptor1.getValue());
        assertEquals(TEST_JOB_ID, markupResult.getJobId());
        assertEquals("output.txt", markupResult.getMarkupUri());
        assertEquals(taskIndex, markupResult.getTaskIndex());
        assertEquals(actionIndex, markupResult.getActionIndex());
        assertEquals(mediaId, markupResult.getMediaId());
        assertEquals(mediaIndex, markupResult.getMediaIndex());
        assertEquals(mediaUri.toString(), markupResult.getSourceUri());
        assertEquals("TEST_MARKUP_PIPELINE", markupResult.getPipeline());

        return markupResult;
    }
}
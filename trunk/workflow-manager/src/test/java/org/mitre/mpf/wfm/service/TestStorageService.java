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


package org.mitre.mpf.wfm.service;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.wfm.camel.operations.detection.artifactextraction.ArtifactExtractionRequest;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.enums.MarkupStatus;
import org.mitre.mpf.wfm.enums.MediaType;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.Assert.*;
import static org.mitre.mpf.test.TestUtil.nonBlank;
import static org.mockito.Mockito.*;

public class TestStorageService {

    @InjectMocks
    private StorageService _storageService;

    @Mock
    private InProgressBatchJobsService _mockInProgressJobs;

    @Mock
    private S3StorageBackend _mockS3Backend;

    @Mock
    private CustomNginxStorageBackend _mockNginxBackend;

    @Mock
    private LocalStorageBackend _mockLocalBackend;

    private static final URI TEST_REMOTE_URI = URI.create("http://somehost.xyz/path");

    private static final URI TEST_LOCAL_URI = URI.create("file:///path");

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }


    @Test
    public void S3BackendHasHigherPriorityThenNginx() throws IOException, StorageException {
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(_mockS3Backend.canStore(outputObject))
                .thenReturn(true);
        when(_mockNginxBackend.canStore(outputObject))
                .thenReturn(true);

        _storageService.store(outputObject);

        verify(_mockS3Backend)
                .store(outputObject);
        verify(_mockNginxBackend, never())
                .store(any(JsonOutputObject.class));
    }


    @Test
    public void canStoreOutputObjectRemotely() throws IOException, StorageException {
        JsonOutputObject outputObject = mock(JsonOutputObject.class);

        when(_mockS3Backend.canStore(outputObject))
                .thenReturn(true);

        when(_mockS3Backend.store(outputObject))
                .thenReturn(TEST_REMOTE_URI);

        URI result = _storageService.store(outputObject);
        assertEquals(TEST_REMOTE_URI, result);

        verifyZeroInteractions(_mockLocalBackend);
        verifyNoInProgressJobWarnings();
    }

    @Test
    public void canStoreOutputObjectLocally() throws IOException, StorageException {
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(_mockLocalBackend.store(outputObject))
                .thenReturn(TEST_LOCAL_URI);

        URI result = _storageService.store(outputObject);

        assertEquals(TEST_LOCAL_URI, result);
        verifyNoInProgressJobWarnings();
        verify(_mockS3Backend)
                .canStore(outputObject);
        verify(_mockNginxBackend)
                .canStore(outputObject);
    }

    @Test
    public void outputObjectGetsStoredLocallyWhenBackendException() throws IOException, StorageException {
        SortedSet<String> warnings = new TreeSet<>();
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobWarnings())
                .thenReturn(warnings);

        when(_mockS3Backend.canStore(outputObject))
                .thenReturn(true);
        doThrow(StorageException.class)
                .when(_mockS3Backend).store(outputObject);

        when(_mockLocalBackend.store(outputObject))
                .thenReturn(TEST_LOCAL_URI);

        URI result = _storageService.store(outputObject);
        assertEquals(TEST_LOCAL_URI, result);

        verifyNoInProgressJobWarnings();
        assertEquals(1, warnings.size());
        assertFalse(StringUtils.isBlank(warnings.first()));
    }


    @Test
    public void outputObjectGetsStoredLocallyWhenCanStoreFails() throws StorageException, IOException {
        SortedSet<String> warnings = new TreeSet<>();
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobWarnings())
                .thenReturn(warnings);

        doThrow(StorageException.class)
                .when(_mockNginxBackend).canStore(outputObject);

        when(_mockLocalBackend.store(outputObject))
                .thenReturn(TEST_LOCAL_URI);

        URI result = _storageService.store(outputObject);
        assertEquals(TEST_LOCAL_URI, result);

        verifyNoInProgressJobWarnings();
        assertEquals(1, warnings.size());
        assertFalse(StringUtils.isBlank(warnings.first()));

        verify(_mockNginxBackend, never())
                .store(any(JsonOutputObject.class));
    }


    @Test
    public void throwsExceptionWhenFailsToStoreLocally() throws IOException, StorageException {
        SortedSet<String> warnings = new TreeSet<>();
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobWarnings())
                .thenReturn(warnings);

        when(_mockS3Backend.canStore(outputObject))
                .thenReturn(true);

        doThrow(StorageException.class)
                .when(_mockS3Backend).store(outputObject);

        doThrow(new IOException("test"))
                .when(_mockLocalBackend).store(outputObject);

        try {
            _storageService.store(outputObject);
            fail("Expected IOException");
        }
        catch (IOException e) {
            assertTrue(e.getSuppressed()[0] instanceof StorageException);
            assertEquals(1, warnings.size());
            assertFalse(StringUtils.isBlank(warnings.first()));
        }
    }


    @Test
    public void canStoreImageArtifactsRemotely() throws IOException, StorageException {
        ArtifactExtractionRequest request = mock(ArtifactExtractionRequest.class);
        when(request.getMediaType())
                .thenReturn(MediaType.IMAGE);

        when(_mockNginxBackend.canStore(request))
                .thenReturn(true);
        when(_mockNginxBackend.storeImageArtifact(request))
                .thenReturn(TEST_REMOTE_URI);

        URI result = _storageService.storeImageArtifact(request);
        assertEquals(TEST_REMOTE_URI, result);

        verifyZeroInteractions(_mockLocalBackend);
        verifyNoInProgressJobWarnings();
    }


    @Test
    public void canStoreImageArtifactsLocally() throws IOException, StorageException {
        ArtifactExtractionRequest request = mock(ArtifactExtractionRequest.class);
        when(request.getMediaType())
                .thenReturn(MediaType.IMAGE);


        when(_mockLocalBackend.storeImageArtifact(request))
                .thenReturn(TEST_LOCAL_URI);

        URI result = _storageService.storeImageArtifact(request);
        assertEquals(TEST_LOCAL_URI, result);

        verifyNoInProgressJobWarnings();
        verify(_mockS3Backend)
                .canStore(request);
        verify(_mockNginxBackend)
                .canStore(request);
    }

    @Test
    public void imageArtifactGetStoredLocallyWhenBackendException() throws IOException, StorageException {
        long jobId = 4231;
        ArtifactExtractionRequest request = mock(ArtifactExtractionRequest.class);
        when(request.getJobId())
                .thenReturn(jobId);
        when(request.getMediaType())
                .thenReturn(MediaType.IMAGE);

        when(_mockNginxBackend.canStore(request))
                .thenReturn(true);
        doThrow(StorageException.class)
                .when(_mockNginxBackend).storeImageArtifact(request);

        when(_mockLocalBackend.storeImageArtifact(request))
                .thenReturn(TEST_LOCAL_URI);

        URI result = _storageService.storeImageArtifact(request);
        assertEquals(TEST_LOCAL_URI, result);

        verifyJobWarning(jobId);
    }


    @Test
    public void canStoreVideoArtifactRemotely() throws IOException, StorageException {
        ArtifactExtractionRequest request = mock(ArtifactExtractionRequest.class);
        when(request.getMediaType())
                .thenReturn(MediaType.VIDEO);

        Map<Integer, URI> expectedResults = ImmutableMap.of(
                0, TEST_REMOTE_URI,
                5, URI.create("http://example"));

        when(_mockS3Backend.canStore(request))
                .thenReturn(true);
        when(_mockS3Backend.storeVideoArtifacts(request))
                .thenReturn(expectedResults);

        Map<Integer, URI> result = _storageService.storeVideoArtifacts(request);
        assertEquals(expectedResults, result);

        verifyZeroInteractions(_mockLocalBackend);
        verifyNoInProgressJobWarnings();
    }


    @Test
    public void canStoreVideoArtifactLocally() throws IOException, StorageException {
        ArtifactExtractionRequest request = mock(ArtifactExtractionRequest.class);
        when(request.getMediaType())
                .thenReturn(MediaType.VIDEO);

        Map<Integer, URI> expectedResults = ImmutableMap.of(
                0, TEST_LOCAL_URI,
                5, URI.create("file:///example"));

        when(_mockLocalBackend.storeVideoArtifacts(request))
                .thenReturn(expectedResults);

        Map<Integer, URI> result = _storageService.storeVideoArtifacts(request);
        assertEquals(expectedResults, result);

        verifyNoInProgressJobWarnings();
        verify(_mockS3Backend)
                .canStore(request);
        verify(_mockNginxBackend)
                .canStore(request);
    }


    @Test
    public void videoArtifactsGetStoredLocallyWhenBackendException() throws IOException, StorageException {
        long jobId = 4233;
        ArtifactExtractionRequest request = mock(ArtifactExtractionRequest.class);
        when(request.getJobId())
                .thenReturn(jobId);
        when(request.getMediaType())
                .thenReturn(MediaType.VIDEO);

        when(_mockNginxBackend.canStore(request))
                .thenReturn(true);
        doThrow(StorageException.class)
                .when(_mockNginxBackend).storeVideoArtifacts(request);

        Map<Integer, URI> expectedResults = ImmutableMap.of(
                0, TEST_LOCAL_URI,
                5, URI.create("file:///example"));

        when(_mockLocalBackend.storeVideoArtifacts(request))
                .thenReturn(expectedResults);

        Map<Integer, URI> result = _storageService.storeVideoArtifacts(request);
        assertEquals(expectedResults, result);

        verifyJobWarning(jobId);
    }


    @Test
    public void canStoreMarkupRemotely() throws IOException, StorageException {
        MarkupResult markup = mock(MarkupResult.class);
        when(_mockS3Backend.canStore(markup))
                .thenReturn(true);

        _storageService.store(markup);

        verify(_mockS3Backend)
                .store(markup);

        verifyNoMarkupError(markup);
        verifyZeroInteractions(_mockLocalBackend);
        verifyNoInProgressJobWarnings();
    }


    @Test
    public void canStoreMarkupLocally() {
        MarkupResult markup = mock(MarkupResult.class);

        _storageService.store(markup);

        verify(_mockLocalBackend)
                .store(markup);
        verifyNoMarkupError(markup);
        verifyNoInProgressJobWarnings();
    }

    @Test
    public void markupStoredLocallyWhenBackendException() throws IOException, StorageException {
        long jobId = 5134;
        MarkupResult markup = mock(MarkupResult.class);
        when(markup.getJobId())
                .thenReturn(jobId);

        when(_mockS3Backend.canStore(markup))
                .thenReturn(true);

        doThrow(StorageException.class)
                .when(_mockS3Backend).store(markup);

        _storageService.store(markup);

        verify(_mockLocalBackend)
                .store(markup);

        verifyJobWarning(jobId);
        verify(markup)
                .setMessage(nonBlank());
        verify(markup)
                .setMarkupStatus(MarkupStatus.COMPLETE_WITH_WARNING);
    }

    private static void verifyNoMarkupError(MarkupResult markup) {
        verify(markup, never())
                .setMessage(any());
        verify(markup, never())
                .setMarkupStatus(any());
    }

    private void verifyNoInProgressJobWarnings() {
        verify(_mockInProgressJobs, never())
                .addJobWarning(anyLong(), any());
    }

    private void verifyJobWarning(long jobId) {
        verify(_mockInProgressJobs)
                .addJobWarning(eq(jobId), nonBlank());
    }
}

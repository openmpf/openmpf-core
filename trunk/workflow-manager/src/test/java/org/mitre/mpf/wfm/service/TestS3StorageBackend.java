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


package org.mitre.mpf.wfm.service;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.ByteStreams;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.test.TestUtil;
import org.mitre.mpf.wfm.camel.operations.detection.artifactextraction.ArtifactExtractionRequest;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.transients.TransientAction;
import org.mitre.mpf.wfm.data.entities.transients.TransientJob;
import org.mitre.mpf.wfm.data.entities.transients.TransientMedia;
import org.mitre.mpf.wfm.data.entities.transients.TransientStage;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import spark.Spark;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestS3StorageBackend {

    private final PropertiesUtil _mockPropertiesUtil = mock(PropertiesUtil.class);

    private final LocalStorageBackend _mockLocalStorageBackend = mock(LocalStorageBackend.class);

    private final PipelineService _mockPipelineService = mock(PipelineService.class);

    private final InProgressBatchJobsService _mockInProgressJobs = mock(InProgressBatchJobsService.class);

    private final S3StorageBackend _s3StorageBackend = new S3StorageBackend(
            _mockPropertiesUtil, _mockLocalStorageBackend, _mockInProgressJobs,
            new AggregateJobPropertiesUtil(_mockPipelineService, null, null));

    @Rule
    public TemporaryFolder _tempFolder = new TemporaryFolder();

    private static final String S3_HOST = "http://localhost:5000/";

    private static final String RESULTS_BUCKET = "RESULTS_BUCKET";

    private static final String EXPECTED_HASH = "5eacf0a11d51413300ee0f4719b7ac7b52b47310a49320703c1d2639ebbc9fea";

    private static final String EXPECTED_OBJECT_KEY = "5e/ac/" + EXPECTED_HASH;

    private static final URI EXPECTED_URI = URI.create(S3_HOST + RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY);

    private static final String BUCKET_WITH_EXISTING_OBJECT = "EXISTING_OBJECT_BUCKET";

    private static final Collection<String> OBJECTS_POSTED = new ArrayList<>();

    private static final AtomicInteger GET_COUNT = new AtomicInteger(0);

    @BeforeClass
    public static void initClass() {
        startSpark();
    }

    @AfterClass
    public static void tearDownClass() {
        Spark.stop();
    }

    @Before
    public void init() {
        OBJECTS_POSTED.clear();
        GET_COUNT.set(0);
    }

    private static Map<String, String> getS3Properties() {
        Map<String, String> properties = new HashMap<>();
        properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + RESULTS_BUCKET);
        properties.put(MpfConstants.S3_SECRET_KEY_PROPERTY, "<MY_SECRET_KEY>");
        properties.put(MpfConstants.S3_ACCESS_KEY_PROPERTY, "<MY_ACCESS_KEY>");
        return properties;
    }

    private Path getTestFileCopy() throws IOException {
        return copyTestFile("/samples/video_01.mp4");
    }

    private Path copyTestFile(String path) throws IOException {
        URI testFileUri = TestUtil.findFile(path);
        Path filePath = _tempFolder.newFolder().toPath().resolve("temp_file");
        Files.copy(Paths.get(testFileUri), filePath);
        return filePath;
    }


    @Test
    public void downloadsFromS3WhenHasKeys() throws StorageException {
        assertTrue(S3StorageBackend.requiresS3MediaDownload(getS3Properties()::get));
    }

    @Test
    public void downloadsFromS3WhenResultsBucketMissing() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_RESULTS_BUCKET_PROPERTY);
        assertTrue(S3StorageBackend.requiresS3MediaDownload(getS3Properties()::get));
    }

    @Test
    public void downloadsFromS3WhenUploadOnlyFalse() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_UPLOAD_ONLY_PROPERTY, "false");
        assertTrue(S3StorageBackend.requiresS3MediaDownload(s3Properties::get));
    }

    @Test
    public void doesNotDownloadFromS3WhenUploadOnlyTrue() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_UPLOAD_ONLY_PROPERTY, "true");
        assertFalse(S3StorageBackend.requiresS3MediaDownload(s3Properties::get));
    }

    @Test(expected = StorageException.class)
    public void doesNotDownloadFromS3WhenNoKeys() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_SECRET_KEY_PROPERTY);
        s3Properties.remove(MpfConstants.S3_ACCESS_KEY_PROPERTY);
        S3StorageBackend.requiresS3MediaDownload(s3Properties::get);
    }

    @Test(expected = StorageException.class)
    public void doesNotDownloadFromS3WhenNoAccessKey() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_ACCESS_KEY_PROPERTY);
        S3StorageBackend.requiresS3MediaDownload(s3Properties::get);
    }

    @Test(expected = StorageException.class)
    public void doesNotDownloadFromS3WhenNoSecretKey() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_SECRET_KEY_PROPERTY);
        S3StorageBackend.requiresS3MediaDownload(s3Properties::get);
    }

    @Test
    public void uploadsToS3WhenHasKeysAndResultsBucket() throws StorageException {
        assertCanUpload(getS3Properties());
    }

    @Test
    public void doesNotUploadToS3WhenResultsBucketMissing() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_RESULTS_BUCKET_PROPERTY);
        assertCanNotUpload(s3Properties);
    }

    @Test
    public void uploadsToS3WhenUploadOnlyFalse() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_UPLOAD_ONLY_PROPERTY, "false");
        assertCanUpload(s3Properties);
    }

    @Test
    public void uploadsToS3WhenUploadOnlyTrue() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_UPLOAD_ONLY_PROPERTY, "true");
        assertCanUpload(s3Properties);
    }


    @Test
    public void doesNotUploadToS3WhenNoKeys() {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_SECRET_KEY_PROPERTY);
        s3Properties.remove(MpfConstants.S3_ACCESS_KEY_PROPERTY);
        assertThrowsWhenCallingCanStore(s3Properties);
    }

    @Test
    public void doesNotUploadToS3WhenNoAccessKey() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_ACCESS_KEY_PROPERTY);
        assertThrowsWhenCallingCanStore(s3Properties);
    }

    @Test
    public void doesNotUploadToS3WhenNoSecretKey() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_SECRET_KEY_PROPERTY);
        assertThrowsWhenCallingCanStore(s3Properties);
    }

    private void assertCanUpload(Map<String, String> properties) throws StorageException {
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobProperties())
                .thenReturn(properties);
        assertTrue(_s3StorageBackend.canStore(outputObject));
    }

    private void assertCanNotUpload(Map<String, String> properties) throws StorageException {
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobProperties())
                .thenReturn(properties);
        assertFalse(_s3StorageBackend.canStore(outputObject));
    }

    private void assertThrowsWhenCallingCanStore(Map<String, String> properties) {
        try {
            JsonOutputObject outputObject = mock(JsonOutputObject.class);
            when(outputObject.getJobProperties())
                    .thenReturn(properties);
            _s3StorageBackend.canStore(outputObject);
            fail("Expected StorageException");
        }
        catch (StorageException expected) {
        }
    }


    @Test
    public void throwsExceptionWhenBadUri() {
        verifyThrowsExceptionWhenDownloading("NOT_A_URI");
        verifyThrowsExceptionWhenDownloading("http:://asdf/qwer/asdf");
        verifyThrowsExceptionWhenDownloading(S3_HOST);
        verifyThrowsExceptionWhenDownloading(S3_HOST + RESULTS_BUCKET);
    }


    private void verifyThrowsExceptionWhenDownloading(String uri) {
        Map<String, String> s3Properties = getS3Properties();

        TransientMedia media = mock(TransientMedia.class);
        when(media.getUri())
                .thenReturn(uri);
        try {
            _s3StorageBackend.downloadFromS3(media, s3Properties::get);
            fail("Expected StorageException");
        }
        catch (StorageException e) {
            assertEquals(0, GET_COUNT.get());
        }
    }


    @Test
    public void throwsExceptionWhenBadResultsBucket() throws IOException {
        Map<String, String> s3Properties = getS3Properties();

        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, "BUCKET");
        verifyThrowsExceptionWhenStoring(s3Properties);

        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST);
        verifyThrowsExceptionWhenStoring(s3Properties);
    }


    private void verifyThrowsExceptionWhenStoring(Map<String, String> s3Properties) throws IOException {
        Path filePath = getTestFileCopy();

        try {
            JsonOutputObject outputObject = mock(JsonOutputObject.class);
            when(outputObject.getJobProperties())
                    .thenReturn(s3Properties);

            when(_mockLocalStorageBackend.store(outputObject))
                    .thenReturn(filePath.toUri());

            _s3StorageBackend.store(outputObject);
            fail("Expected StorageException");
        }
        catch(StorageException e) {
            Files.exists(filePath);
        }
    }



    @Test
    public void canStoreImageArtifacts() throws IOException, StorageException {
        ArtifactExtractionRequest request = createArtifactExtractionRequest();

        Path filePath = getTestFileCopy();
        when(_mockLocalStorageBackend.storeImageArtifact(request))
                .thenReturn(filePath.toUri());

        assertTrue(_s3StorageBackend.canStore(request));

        URI remoteUri = _s3StorageBackend.storeImageArtifact(request);
        assertEquals(EXPECTED_URI, remoteUri);
        assertFalse(Files.exists(filePath));
        assertEquals(Collections.singletonList(RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY), OBJECTS_POSTED);
    }



    @Test
    public void canStoreVideoArtifacts() throws IOException, StorageException {
        ArtifactExtractionRequest request = createArtifactExtractionRequest();

        Path filePath0 = getTestFileCopy();
        Path filePath1 = copyTestFile("/samples/meds1.jpg");
        when(_mockLocalStorageBackend.storeVideoArtifacts(request))
                .thenReturn(ImmutableSortedMap.of(0, filePath0.toUri(), 1, filePath1.toUri()));

        assertTrue(_s3StorageBackend.canStore(request));
        Map<Integer, URI> results = _s3StorageBackend.storeVideoArtifacts(request);
        Map<Integer, URI> expectedResults = ImmutableMap.of(
                0, EXPECTED_URI,
                1, filePath1.toUri());

        assertEquals(expectedResults, results);
        assertFalse(Files.exists(filePath0));
        assertTrue(Files.exists(filePath1));
        assertTrue(OBJECTS_POSTED.contains(RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY));
        assertTrue(OBJECTS_POSTED.contains(
                RESULTS_BUCKET + "/c0/67/c067e7eed23a0fe022140c30dbfa993ae720309d6567a803d111ecec739a6713"));
    }


    private ArtifactExtractionRequest createArtifactExtractionRequest() {
        long jobId = 1243;
        long mediaId = 432;

        TransientMedia media = mock(TransientMedia.class);

        TransientJob job = mock(TransientJob.class);
        when(job.getMedia(mediaId))
                .thenReturn(media);
        when(job.getOverriddenJobProperties())
                .thenReturn(ImmutableMap.of(
                        MpfConstants.S3_ACCESS_KEY_PROPERTY, "<ACCESS_KEY>",
                        MpfConstants.S3_SECRET_KEY_PROPERTY, ""
                ));

        when(media.getMediaSpecificProperties())
                .thenReturn(ImmutableMap.of(
                        MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + RESULTS_BUCKET,
                        MpfConstants.S3_SECRET_KEY_PROPERTY, "<SECRET_KEY>"
                ));

        when(_mockInProgressJobs.getJob(jobId))
                .thenReturn(job);


        ArtifactExtractionRequest request = mock(ArtifactExtractionRequest.class);
        when(request.getJobId())
                .thenReturn(jobId);
        when(request.getMediaId())
                .thenReturn(mediaId);

        return request;
    }


    @Test
    public void canStoreMarkupRequest() throws IOException, StorageException {
        long jobId = 534;
        long mediaId = 421;
        Path filePath = getTestFileCopy();

        MarkupResult markupResult = mock(MarkupResult.class);
        TransientJob job = mock(TransientJob.class, RETURNS_DEEP_STUBS);
        TransientMedia media = mock(TransientMedia.class);
        TransientStage stage = mock(TransientStage.class, RETURNS_DEEP_STUBS);
        TransientAction action = mock(TransientAction.class);

        when(markupResult.getJobId())
                .thenReturn(jobId);
        when(markupResult.getMediaId())
                .thenReturn(mediaId);
        when(markupResult.getMarkupUri())
                .thenReturn(filePath.toUri().toString());

        when(job.getMedia(mediaId))
                .thenReturn(media);

        when(job.getPipeline().getStages().get(0))
                .thenReturn(stage);

        when(stage.getActions().get(0))
                .thenReturn(action);

        when(action.getAlgorithm())
                .thenReturn("TEST_ALGO");


        when(media.getMediaSpecificProperties())
                .thenReturn(ImmutableMap.of(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + RESULTS_BUCKET));

        when(action.getProperties())
                .thenReturn(ImmutableMap.of(MpfConstants.S3_ACCESS_KEY_PROPERTY, "<ACCESS_KEY>"));

        when(job.getOverriddenAlgorithmProperties().row("TEST_ALGO"))
                .thenReturn(ImmutableMap.of(MpfConstants.S3_SECRET_KEY_PROPERTY, "<SECRET_KEY>"));

        when(job.getOverriddenJobProperties())
                .thenReturn(ImmutableMap.of());

        when(_mockInProgressJobs.getJob(jobId))
                .thenReturn(job);

        assertTrue(_s3StorageBackend.canStore(markupResult));


        _s3StorageBackend.store(markupResult);

        verify(markupResult)
                .setMarkupUri(EXPECTED_URI.toString());

        assertFalse(Files.exists(filePath));
        assertEquals(Collections.singletonList(RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY), OBJECTS_POSTED);
    }


    @Test
    public void canStoreJsonOutputObject() throws IOException, StorageException {
        Path filePath = getTestFileCopy();

        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobProperties())
                .thenReturn(getS3Properties());

        when(_mockLocalStorageBackend.store(outputObject))
                .thenReturn(filePath.toUri());

        URI remoteUri = _s3StorageBackend.store(outputObject);
        assertEquals(EXPECTED_URI, remoteUri);
        assertFalse(Files.exists(filePath));
        assertEquals(Collections.singletonList(RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY), OBJECTS_POSTED);
    }


    @Test
    public void doesNotStoreDuplicateOutputObject() throws IOException, StorageException {
        Path filePath = getTestFileCopy();

        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + BUCKET_WITH_EXISTING_OBJECT);

        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobProperties())
                .thenReturn(s3Properties);

        when(_mockLocalStorageBackend.store(outputObject))
                .thenReturn(filePath.toUri());

        URI remoteUri = _s3StorageBackend.store(outputObject);
        assertEquals(URI.create(S3_HOST + BUCKET_WITH_EXISTING_OBJECT + '/' + EXPECTED_OBJECT_KEY), remoteUri);
        assertFalse(Files.exists(filePath));
        assertTrue(OBJECTS_POSTED.isEmpty());
    }


    @Test
    public void canHandleConnectionRefused() throws IOException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, "http://localhost:5001/" + RESULTS_BUCKET);
        Path filePath = getTestFileCopy();

        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobProperties())
                .thenReturn(s3Properties);

        when(_mockLocalStorageBackend.store(outputObject))
                .thenReturn(filePath.toUri());

        try {
            _s3StorageBackend.store(outputObject);
            fail("Expected StorageException to be thrown.");
        }
        catch (StorageException expected) {
            assertTrue(OBJECTS_POSTED.isEmpty());
            assertTrue(Files.exists(filePath));
        }
    }


    @Test
    public void canRetryUploadWhenServerError() throws IOException {
        int retryCount = 2;
        when(_mockPropertiesUtil.getHttpStorageUploadRetryCount())
                .thenReturn(retryCount);

        Path filePath = getTestFileCopy();
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + "BAD_BUCKET");

        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobProperties())
                .thenReturn(s3Properties);

        when(_mockLocalStorageBackend.store(outputObject))
                .thenReturn(filePath.toUri());

        try {
            _s3StorageBackend.store(outputObject);
            fail("Expected StorageException to be thrown.");
        }
        catch (StorageException expected) {
            assertTrue(Files.exists(filePath));
            String expectedObject = "BAD_BUCKET/" + EXPECTED_OBJECT_KEY;
            long numAttempts = OBJECTS_POSTED.stream()
                    .filter(expectedObject::equals)
                    .count();
            assertEquals(retryCount + 1, numAttempts);
        }
    }


    @Test
    public void canDownloadFromS3() throws IOException, StorageException {
        Map<String, String> s3Properties = getS3Properties();
        Path localPath = _tempFolder.newFolder().toPath().resolve("temp_downloaded_media");

        TransientMedia media = mock(TransientMedia.class);
        when(media.getUri())
                .thenReturn(EXPECTED_URI.toString());
        when(media.getLocalPath())
                .thenReturn(localPath);

        assertFalse(Files.exists(localPath));

        _s3StorageBackend.downloadFromS3(media, s3Properties::get);

        assertTrue(Files.exists(localPath));
        String sha;
        try (InputStream is = Files.newInputStream(localPath)) {
            sha = DigestUtils.sha256Hex(is);
        }
        assertEquals(EXPECTED_HASH, sha);
    }


    @Test
    public void throwsStorageExceptionWhenRemoteFileMissing() throws IOException {
        Path localPath = _tempFolder.newFolder().toPath().resolve("temp_downloaded_media");

        TransientMedia media = mock(TransientMedia.class);
        when(media.getUri())
                .thenReturn(S3_HOST + "BAD_BUCKET/12/34/1234567");
        when(media.getLocalPath())
                .thenReturn(localPath);

        try {
            _s3StorageBackend.downloadFromS3(media, getS3Properties()::get);
            fail("Expected StorageException");
        }
        catch (StorageException e) {
            assertFalse(Files.exists(localPath));
        }
    }


    private static void startSpark() {
        Spark.port(5000);

        // S3 client uses the HTTP HEAD method to check if object exists.
        Spark.head("/:bucket/*", (req, resp) -> {
            String bucket = req.params(":bucket");
            String key = req.splat()[0];
            if (BUCKET_WITH_EXISTING_OBJECT.equals(bucket) && EXPECTED_OBJECT_KEY.equals(key)) {
                Spark.halt(200);
            }
            Spark.halt(404);
            return "";
        });

        Spark.get("/:bucket/*", (req, resp) -> {
            GET_COUNT.incrementAndGet();
            String bucket = req.params(":bucket");
            String key = req.splat()[0];
            if (!RESULTS_BUCKET.equals(bucket) || !EXPECTED_OBJECT_KEY.equals(key)) {
                Spark.halt(404);
            }
            Path path = Paths.get(TestUtil.findFile("/samples/video_01.mp4"));
            long fileSize = Files.size(path);
            resp.header("Content-Length", String.valueOf(fileSize));

            try (OutputStream out = resp.raw().getOutputStream()) {
                Files.copy(path, out);
            }
            resp.raw().flushBuffer();
            return "";
        });

        Spark.put("/:bucket/*", (req, resp) -> {
            String bucket = req.params(":bucket");
            String key = req.splat()[0];
            OBJECTS_POSTED.add(bucket + '/' + key);
            if (!RESULTS_BUCKET.equals(bucket) || !EXPECTED_OBJECT_KEY.equals(key)) {
                Spark.halt(500);
            }
            try (InputStream is = req.raw().getInputStream()) {
                ByteStreams.exhaust(is);
            }
            return "";
        });

        Spark.awaitInitialization();
    }
}

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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.common.io.ByteStreams;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.rest.api.pipelines.*;
import org.mitre.mpf.test.TestUtil;
import org.mitre.mpf.wfm.camel.operations.detection.artifactextraction.ArtifactExtractionRequest;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.data.entities.persistent.JobPipelineElements;
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
import java.util.function.IntUnaryOperator;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestS3StorageBackend {

    private final PropertiesUtil _mockPropertiesUtil = mock(PropertiesUtil.class);

    private final LocalStorageBackend _mockLocalStorageBackend = mock(LocalStorageBackend.class);

    private final InProgressBatchJobsService _mockInProgressJobs = mock(InProgressBatchJobsService.class);

    private final S3StorageBackend _s3StorageBackend = new S3StorageBackend(
            _mockPropertiesUtil, _mockLocalStorageBackend, _mockInProgressJobs,
            new AggregateJobPropertiesUtil(_mockPropertiesUtil, null));

    @Rule
    public TemporaryFolder _tempFolder = new TemporaryFolder();

    private static final String S3_HOST = "http://localhost:5000/";

    private static final String RESULTS_BUCKET = "RESULTS_BUCKET";

    private static final String EXPECTED_HASH = "5eacf0a11d51413300ee0f4719b7ac7b52b47310a49320703c1d2639ebbc9fea";

    private static final String EXPECTED_OBJECT_KEY = "5e/ac/" + EXPECTED_HASH;

    private static final URI EXPECTED_URI = URI.create(S3_HOST + RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY);

    private static final String BUCKET_WITH_EXISTING_OBJECT = "EXISTING_OBJECT_BUCKET";

    private static final Collection<String> OBJECTS_POSTED = Collections.synchronizedList(new ArrayList<>());

    private static final AtomicInteger GET_COUNT = new AtomicInteger(0);

    private static final AtomicInteger REQUESTED_GET_FAILURES = new AtomicInteger(0);

    private static final AtomicInteger REQUESTED_PUT_FAILURES = new AtomicInteger(0);

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
        REQUESTED_GET_FAILURES.set(0);
        REQUESTED_PUT_FAILURES.set(0);
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

    @Test
    public void doesNotDownloadFromS3WhenNoKeys() throws StorageException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.remove(MpfConstants.S3_SECRET_KEY_PROPERTY);
        s3Properties.remove(MpfConstants.S3_ACCESS_KEY_PROPERTY);
        assertFalse(S3StorageBackend.requiresS3MediaDownload(s3Properties::get));
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
        JsonOutputObject outputObject = setJobProperties(properties);
        assertTrue(_s3StorageBackend.canStore(outputObject));
    }

    private void assertCanNotUpload(Map<String, String> properties) throws StorageException {
        JsonOutputObject outputObject = setJobProperties(properties);
        assertFalse(_s3StorageBackend.canStore(outputObject));
    }

    private void assertThrowsWhenCallingCanStore(Map<String, String> properties) {
        try {
            JsonOutputObject outputObject = setJobProperties(properties);
            _s3StorageBackend.canStore(outputObject);
            fail("Expected StorageException");
        }
        catch (StorageException expected) {
        }
    }

    private JsonOutputObject setJobProperties(Map<String, String> properties) {
        long jobId = 123;
        JsonOutputObject outputObject = mock(JsonOutputObject.class);
        when(outputObject.getJobId())
                .thenReturn(jobId);

        var mockJob = mock(BatchJob.class);
        when(mockJob.getJobProperties())
                .thenReturn(ImmutableMap.copyOf(properties));
        when(_mockInProgressJobs.getJob(jobId))
                .thenReturn(mockJob);
        return outputObject;
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

        Media media = mock(Media.class);
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
            JsonOutputObject outputObject = setJobProperties(s3Properties);
            when(_mockLocalStorageBackend.store(same(outputObject), any()))
                    .thenReturn(filePath.toUri());

            _s3StorageBackend.store(outputObject, new MutableObject<>());
            fail("Expected StorageException");
        }
        catch(StorageException e) {
            Files.exists(filePath);
        }
    }



    @Test
    public void canStoreArtifacts() throws IOException, StorageException {
        ArtifactExtractionRequest request = createArtifactExtractionRequest();

        Path filePath0 = getTestFileCopy();
        Path filePath1 = copyTestFile("/samples/meds1.jpg");
        when(_mockLocalStorageBackend.storeArtifacts(request))
                .thenReturn(new ImmutableTable.Builder<Integer, Integer, URI>()
                        .put(0, 2, filePath0.toUri())
                        .put(1, 3, filePath1.toUri())
                        .build());

        assertTrue(_s3StorageBackend.canStore(request));
        Table<Integer, Integer, URI> results = _s3StorageBackend.storeArtifacts(request);
        Table<Integer, Integer, URI> expectedResults = new ImmutableTable.Builder<Integer, Integer, URI>()
                                                       .put(0, 2, EXPECTED_URI)
                                                       .put(1, 3, filePath1.toUri())
                                                       .build();

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

        var media = mock(Media.class);

        var job = mock(BatchJob.class);
        when(job.getMedia(mediaId))
                .thenReturn(media);
        when(job.getJobProperties())
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


        var request = mock(ArtifactExtractionRequest.class);
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

        var markupResult = mock(MarkupResult.class);
        var job = mock(BatchJob.class, RETURNS_DEEP_STUBS);
        var media = mock(Media.class);

        var algorithm = new Algorithm("TEST_ALGO", "description", ActionType.DETECTION,
                                      new Algorithm.Requires(List.of()),
                                      new Algorithm.Provides(List.of(), List.of()),
                                      true, true);
        var action = new Action(
                "TEST_ACTION", "description", algorithm.getName(),
                List.of(new ActionProperty(MpfConstants.S3_ACCESS_KEY_PROPERTY,
                                                              "<ACCESS_KEY>")));
        var task = new Task("TEST_TASK", "description", List.of(action.getName()));
        var pipeline = new Pipeline("TEST_PIPELINE", "description",
                                    List.of(task.getName()));
        var pipelineElements = new JobPipelineElements(
                pipeline, List.of(task), List.of(action),
                List.of(algorithm));

        when(markupResult.getJobId())
                .thenReturn(jobId);
        when(markupResult.getMediaId())
                .thenReturn(mediaId);
        when(markupResult.getMarkupUri())
                .thenReturn(filePath.toUri().toString());

        when(job.getMedia(mediaId))
                .thenReturn(media);

        when(job.getPipelineElements())
                .thenReturn(pipelineElements);


        when(media.getMediaSpecificProperties())
                .thenReturn(ImmutableMap.of(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + RESULTS_BUCKET));

        var overriddenAlgoProps
                = ImmutableMap.of("TEST_ALGO",
                                  ImmutableMap.of(MpfConstants.S3_SECRET_KEY_PROPERTY, "<SECRET_KEY>"));
        when(job.getOverriddenAlgorithmProperties())
                .thenReturn(overriddenAlgoProps);

        when(job.getJobProperties())
                .thenReturn(ImmutableMap.of());

        when(_mockInProgressJobs.getJob(jobId))
                .thenReturn(job);

        assertTrue(_s3StorageBackend.canStore(markupResult));


        _s3StorageBackend.store(markupResult);

        verify(markupResult)
                .setMarkupUri(EXPECTED_URI.toString());

        assertFalse(Files.exists(filePath));
        assertEquals(List.of(RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY), OBJECTS_POSTED);
    }


    @Test
    public void canStoreJsonOutputObject() throws IOException, StorageException {
        Path filePath = getTestFileCopy();

        JsonOutputObject outputObject = setJobProperties(getS3Properties());
        when(_mockLocalStorageBackend.store(same(outputObject), any()))
                .thenReturn(filePath.toUri());

        URI remoteUri = _s3StorageBackend.store(outputObject, new MutableObject<>());
        assertEquals(EXPECTED_URI, remoteUri);
        assertFalse(Files.exists(filePath));
        assertEquals(List.of(RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY), OBJECTS_POSTED);
    }


    @Test
    public void doesNotStoreDuplicateOutputObject() throws IOException, StorageException {
        Path filePath = getTestFileCopy();

        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + BUCKET_WITH_EXISTING_OBJECT);

        JsonOutputObject outputObject = setJobProperties(s3Properties);
        when(_mockLocalStorageBackend.store(same(outputObject), any()))
                .thenReturn(filePath.toUri());

        URI remoteUri = _s3StorageBackend.store(outputObject, new MutableObject<>());
        assertEquals(URI.create(S3_HOST + BUCKET_WITH_EXISTING_OBJECT + '/' + EXPECTED_OBJECT_KEY), remoteUri);
        assertFalse(Files.exists(filePath));
        assertTrue(OBJECTS_POSTED.isEmpty());
    }


    @Test
    public void canHandleConnectionRefused() throws IOException {
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, "http://localhost:5001/" + RESULTS_BUCKET);
        Path filePath = getTestFileCopy();

        JsonOutputObject outputObject = setJobProperties(s3Properties);
        when(_mockLocalStorageBackend.store(same(outputObject), any()))
                .thenReturn(filePath.toUri());

        try {
            _s3StorageBackend.store(outputObject, new MutableObject<>());
            fail("Expected StorageException to be thrown.");
        }
        catch (StorageException expected) {
            assertTrue(OBJECTS_POSTED.isEmpty());
            assertTrue(Files.exists(filePath));
        }
    }


    @Test
    public void canRetryUploadAndFailWhenServerError() throws IOException {
        int retryCount = 2;
        when(_mockPropertiesUtil.getHttpStorageUploadRetryCount())
                .thenReturn(retryCount);

        Path filePath = getTestFileCopy();
        Map<String, String> s3Properties = getS3Properties();
        s3Properties.put(MpfConstants.S3_RESULTS_BUCKET_PROPERTY, S3_HOST + "BAD_BUCKET");

        JsonOutputObject outputObject = setJobProperties(s3Properties);
        when(_mockLocalStorageBackend.store(same(outputObject), any()))
                .thenReturn(filePath.toUri());

        try {
            _s3StorageBackend.store(outputObject, new MutableObject<>());
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
    public void canRetryUploadAndRecoverWhenServerError() throws IOException, StorageException {
        REQUESTED_PUT_FAILURES.set(2);
        when(_mockPropertiesUtil.getHttpStorageUploadRetryCount())
                .thenReturn(2);

        Path filePath = getTestFileCopy();

        JsonOutputObject outputObject = setJobProperties(getS3Properties());
        when(_mockLocalStorageBackend.store(same(outputObject), any()))
                .thenReturn(filePath.toUri());

        URI remoteUri = _s3StorageBackend.store(outputObject, new MutableObject<>());
        assertEquals(EXPECTED_URI, remoteUri);
        assertFalse(Files.exists(filePath));

        // two failures one success
        assertEquals(3, OBJECTS_POSTED.size());

        String expectedObject = RESULTS_BUCKET + '/' + EXPECTED_OBJECT_KEY;
        boolean allCorrectObject = OBJECTS_POSTED.stream()
                .allMatch(expectedObject::equals);
        assertTrue(allCorrectObject);
    }


    @Test
    public void canDownloadFromS3() throws IOException, StorageException {
        Map<String, String> s3Properties = getS3Properties();
        Path localPath = _tempFolder.newFolder().toPath().resolve("temp_downloaded_media");

        Media media = mock(Media.class);
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
    public void canRetryDownloadAndFailWhenServerError() throws IOException {
        int retryCount = 2;
        when(_mockPropertiesUtil.getRemoteMediaDownloadRetries())
                .thenReturn(retryCount);

        Path localPath = _tempFolder.newFolder().toPath().resolve("temp_downloaded_media");

        Media media = mock(Media.class);
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
            assertEquals(retryCount + 1, GET_COUNT.get());
        }
    }


    @Test
    public void canRetryDownloadAndRecoverWhenServerError() throws IOException, StorageException {
        REQUESTED_GET_FAILURES.set(2);
        when(_mockPropertiesUtil.getRemoteMediaDownloadRetries())
                .thenReturn(2);

        Path localPath = _tempFolder.newFolder().toPath().resolve("temp_downloaded_media");
        Media media = mock(Media.class);
        when(media.getUri())
                .thenReturn(EXPECTED_URI.toString());
        when(media.getLocalPath())
                .thenReturn(localPath);

        assertFalse(Files.exists(localPath));

        _s3StorageBackend.downloadFromS3(media, getS3Properties()::get);

        assertTrue(Files.exists(localPath));
        String sha;
        try (InputStream is = Files.newInputStream(localPath)) {
            sha = DigestUtils.sha256Hex(is);
        }
        assertEquals(EXPECTED_HASH, sha);

        // Two failed attempts and one successful
        assertEquals(3, GET_COUNT.get());
    }


    @Test
    public void throwsStorageExceptionWhenRemoteFileMissing() throws IOException {
        Path localPath = _tempFolder.newFolder().toPath().resolve("temp_downloaded_media");

        Media media = mock(Media.class);
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
        IntUnaryOperator decrementUntilZero = i -> Math.max(i - 1, 0);

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
            if (REQUESTED_GET_FAILURES.getAndUpdate(decrementUntilZero) > 0
                    || !RESULTS_BUCKET.equals(bucket)
                    || !EXPECTED_OBJECT_KEY.equals(key)) {
                Spark.halt(500);
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

            if (REQUESTED_PUT_FAILURES.getAndUpdate(decrementUntilZero) > 0
                    || !RESULTS_BUCKET.equals(bucket)
                    || !EXPECTED_OBJECT_KEY.equals(key)) {
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

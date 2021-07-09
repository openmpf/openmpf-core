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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Table;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.wfm.camel.operations.detection.artifactextraction.ArtifactExtractionRequest;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mitre.mpf.wfm.util.PipeStream;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.mitre.mpf.wfm.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

@Service
public class CustomNginxStorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(CustomNginxStorageBackend.class);

    private final PropertiesUtil _propertiesUtil;

    private final ObjectMapper _objectMapper;

    private final InProgressBatchJobsService _inProgressBatchJobs;


    @Inject
    CustomNginxStorageBackend(
            PropertiesUtil propertiesUtil,
            ObjectMapper objectMapper,
            InProgressBatchJobsService inProgressBatchJobs) {

        _propertiesUtil = propertiesUtil;
        _objectMapper = objectMapper;
        _inProgressBatchJobs = inProgressBatchJobs;
    }


    @Override
    public boolean canStore(JsonOutputObject outputObject) throws StorageException {
        return canStore(outputObject.getJobId());
    }

    private boolean canStore(long jobId) throws StorageException {
        return _inProgressBatchJobs.getJob(jobId)
                .getSystemPropertiesSnapshot()
                .getNginxStorageServiceUri()
                .isPresent();
    }

    @Override
    public URI store(JsonOutputObject outputObject, Mutable<String> outputSha) throws StorageException, IOException {
        URI serviceUri = getServiceUri(outputObject.getJobId());
        if (outputSha.getValue() == null) {
            MessageDigest digest = DigestUtils.getSha256Digest();
            URI result;
            try (var inputStream = new DigestInputStream(createJsonInputStream(outputObject), digest)) {
                result = store(serviceUri, inputStream);
            }
            outputSha.setValue(Hex.encodeHexString(digest.digest()));
            return result;
        }
        else {
            try (PipeStream inputStream = createJsonInputStream(outputObject)) {
                return store(serviceUri, inputStream);
            }
        }
    }

    @Override
    public boolean canStore(MarkupResult markupResult) throws StorageException {
        return canStore(markupResult.getJobId());
    }


    @Override
    public void store(MarkupResult markupResult) throws IOException, StorageException {
        Path localPath = Paths.get(URI.create(markupResult.getMarkupUri()));
        URI serviceUri = getServiceUri(markupResult.getJobId());
        URI newLocation;
        try (InputStream inputStream = Files.newInputStream(localPath)) {
            newLocation = store(serviceUri, inputStream);
        }
        markupResult.setMarkupUri(newLocation.toString());
        Files.delete(localPath);
    }


    @Override
    public boolean canStore(ArtifactExtractionRequest request) throws StorageException {
        return canStore(request.getJobId());
    }


    @Override
    public Table<Integer, Integer, URI> storeArtifacts(ArtifactExtractionRequest request) throws IOException, StorageException {
        URI serviceUri = getServiceUri(request.getJobId());
        return StreamableFrameExtractor.run(request, inStream -> store(serviceUri, inStream));
    }


    private URI getServiceUri(long jobId) throws StorageException {
        return _inProgressBatchJobs.getJob(jobId)
                .getSystemPropertiesSnapshot()
                .getNginxStorageServiceUri()
                // The URI should always be present at this point since it was checked in canStore().
                .orElseThrow(() -> new StorageException(String.format("Unable to store job %d in Nginx.", jobId)));
    }


    public URI store(URI serviceUri, InputStream content) throws StorageException {
        try (RetryingHttpClient client = new RetryingHttpClient(_propertiesUtil)) {
            String uploadId = initUpload(client, serviceUri);
            List<FilePartETag> filePartETags = sendContent(client, serviceUri, uploadId, content);
            return completeUpload(client, serviceUri, uploadId, filePartETags);
        }
    }


    private String initUpload(RetryingHttpClient client, URI serviceUri) throws StorageException {
        try (HttpResponseWrapper response = client.execute(new HttpPost(getInitUri(serviceUri)))) {
            JsonNode jsonNode = _objectMapper.readTree(response.getEntity().getContent());
            String uploadId = jsonNode.path("upload_id").asText(null);
            if (uploadId == null) {
                throw new StorageException(
                        "Upload init HTTP response did not contain the \"upload_id\" field as expected.");
            }
            return uploadId;
        }
        catch (IOException e) {
            throw new StorageException("Upload init HTTP request failed due to: " + e, e);
        }
    }

    private static URI getInitUri(URI serviceUri) throws StorageException {
        try {
            return buildUploadUri(serviceUri)
                    .addParameter("init", null)
                    .build();
        }
        catch (URISyntaxException e) {
            throw new StorageException("An error occurred while trying to build the init upload URI: " + e, e);
        }
    }


    private List<FilePartETag> sendContent(RetryingHttpClient client, URI serviceUri, String uploadId,
                                           InputStream content) throws StorageException {
        try {
            int uploadThreadCount = _propertiesUtil.getNginxStorageUploadThreadCount();
            int uploadSegmentSize = _propertiesUtil.getNginxStorageUploadSegmentSize();

            Dispatcher dispatcher = new Dispatcher(client, serviceUri, uploadId, content);

            List<CompletableFuture<Stream<FilePartETag>>> futures = new ArrayList<>(uploadThreadCount);
            for (int i = 0; i < uploadThreadCount; i++) {
                futures.add(ThreadUtil.callAsync(() -> worker(dispatcher, uploadSegmentSize)));
            }

            return futures.stream()
                    .flatMap(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(f -> f.PartNumber))
                    .collect(toList());
        }
        catch (CompletionException e) {
            unwrapAndThrow(e, String.format("An error occurred while sending content for upload id \"%s\": ",
                                            uploadId));
            // Unreachable
            return null;
        }
    }

    private static void unwrapAndThrow(CompletionException completionException, String message)
            throws StorageException {
        Throwable uploadError = completionException.getCause();
        if (uploadError == null) {
            throw new RuntimeException(message + completionException, completionException);
        }

        try {
            throw uploadError;
        }
        catch (IOException | IllegalStateException e) {
            throw new StorageException(message + e, e);
        }
        catch (StorageException | Error | RuntimeException e) {
            throw e;
        }
        catch (Throwable e) {
            throw new RuntimeException(message + e, e);
        }
    }


    private static FilePartETag sendPart(RetryingHttpClient client, URI serviceUri, String uploadId, int partNumber,
                                  byte[] content) throws StorageException {

        HttpEntity fileUploadEntity = MultipartEntityBuilder.create()
                .addBinaryBody("file", content, ContentType.DEFAULT_BINARY, "part_" + partNumber)
                .build();
        HttpPost post = new HttpPost(getSendPartUri(serviceUri, uploadId, partNumber));
        post.setEntity(fileUploadEntity);

        try (HttpResponseWrapper response = client.execute(post)) {
            Header eTagHeader = response.getFirstHeader("ETag");
            if (eTagHeader == null) {
                throw new StorageException(String.format(
                        "The HTTP response for upload id \"%s\"'s part number %d did not contain an \"ETag\" header.",
                        uploadId, partNumber));
            }
            return new FilePartETag(partNumber, eTagHeader.getValue());
        }
        catch (IOException e) {
            throw new StorageException(String.format(
                    "An error occurred while trying to upload part number %d for upload id \"%s\": %s",
                    partNumber, uploadId, e), e);
        }
    }


    private static URI getSendPartUri(URI serviceUri, String uploadId, int partNumber) throws StorageException {
        try {
            return buildUploadUri(serviceUri)
                    .addParameter("uploadID", uploadId)
                    .addParameter("partNumber", String.valueOf(partNumber))
                    .build();
        }
        catch (URISyntaxException e) {
            throw new StorageException(String.format(
                    "An error occurred while trying to build the URI to send part number %d for upload id \"%s\": %s",
                    partNumber, uploadId, e), e);
        }
    }


    private URI completeUpload(RetryingHttpClient client, URI serviceUri, String uploadId, List<FilePartETag> eTags)
            throws StorageException {
        HttpPost post = new HttpPost(getCompleteUploadUri(serviceUri, uploadId));
        post.setEntity(createCompleteUploadJson(eTags));

        try (HttpResponseWrapper response = client.execute(post)) {
            JsonNode jsonRoot = _objectMapper.readTree(response.getEntity().getContent());

            String pathToRelativeUrl = "/status/0/relative_url";
            String relativeUrl = jsonRoot.at(pathToRelativeUrl).asText(null);
            if (relativeUrl == null) {
                throw new StorageException("Unexpected response content. Expected to find JSON path: "
                                                   + pathToRelativeUrl);
            }

            return new URIBuilder(serviceUri)
                    .setPath(relativeUrl)
                    .build();
        }
        catch (IOException e) {
            throw new StorageException(String.format(
                    "An error occurred while trying to send the complete upload HTTP request for upload id \"%s\": %s",
                    uploadId, e), e);
        }
        catch (URISyntaxException e) {
            throw new StorageException(String.format(
                    "An error occurred while trying to build the retrieval URI for upload id \"%s\": %s",
                    uploadId, e), e);
        }
    }


    private HttpEntity createCompleteUploadJson(List<FilePartETag> eTags) {
        ObjectNode completeUploadJson = _objectMapper.createObjectNode();
        completeUploadJson.putPOJO("CompleteMultipartUpload", eTags);
        return new AbstractHttpEntity() {

            public void writeTo(OutputStream outStream) throws IOException {
                _objectMapper.writeValue(outStream, completeUploadJson);
            }

            public boolean isRepeatable() { return true; }

            public long getContentLength() { return -1; }

            public boolean isStreaming() { return false; }

            public InputStream getContent() { throw new UnsupportedOperationException("Only writeTo is supported."); }
        };
    }


    private static URI getCompleteUploadUri(URI serviceUri, String uploadId) throws StorageException {
        try {
            return buildUploadUri(serviceUri)
                    .addParameter("uploadID", uploadId)
                    .build();
        }
        catch (URISyntaxException e) {
            throw new StorageException(String.format(
                    "An error occurred while trying to create the complete upload URI for upload id \"%s\": %s",
                    uploadId, e), e);
        }
    }


    private static URIBuilder buildUploadUri(URI serviceUri) {
        URIBuilder builder = new URIBuilder(serviceUri);
        builder.setPath(builder.getPath() + "/api/uploadS3.php");
        return builder;
    }


    private static final int JSON_PIPE_STREAM_BUFFER_SIZE = 16384;

    private PipeStream createJsonInputStream(Object object) throws StorageException {
        try {
            return new PipeStream(JSON_PIPE_STREAM_BUFFER_SIZE, out -> _objectMapper.writeValue(out, object));
        }
        catch (IOException e) {
            throw new StorageException("An error occurred while trying to convert object to JSON: " + e, e);
        }
    }


    private static class Dispatcher {
        private final RetryingHttpClient _client;
        private final URI _serviceUri;
        private final String _uploadId;
        private final InputStream _content;

        private int _partCount = -1;

        public Dispatcher(RetryingHttpClient client, URI serviceUri, String uploadId, InputStream content) {
            _client = client;
            _uploadId = uploadId;
            _content = content;
            _serviceUri = serviceUri;
        }

        public synchronized Callable<FilePartETag> getWork(byte[] buffer) throws StorageException {
            _partCount += 1;
            int numRead;
            try {
                numRead = readUntilEndOrFull(buffer, _content);
            }
            catch (IOException e) {
                throw new StorageException(String.format(
                        "An error occurred while trying to get the data to upload for part number %d for upload id \"%s\": %s",
                        _partCount, _uploadId, e), e);
            }

            if (numRead < 1) {
                return null;
            }
            final byte[] sendBuffer = numRead == buffer.length
                    ? buffer
                    : Arrays.copyOf(buffer, numRead);
            log.debug("Started sending part number: {}", _partCount);
            final int partCount = _partCount;
            return () -> sendPart(_client, _serviceUri, _uploadId, partCount, sendBuffer);
        }
    }


    private static int readUntilEndOrFull(byte[] buffer, InputStream stream) throws IOException {
        int totalRead = 0;
        while (true) {
            int read = stream.read(buffer, totalRead, buffer.length - totalRead);
            if (read < 1) {
                return totalRead;
            }
            totalRead += read;
            if (totalRead == buffer.length) {
                return totalRead;
            }
        }
    }

    private static Stream<FilePartETag> worker(Dispatcher dispatcher, int partSize) throws Exception {
        Stream.Builder<FilePartETag> resultsBuilder = Stream.builder();
        byte[] buffer = new byte[partSize];

        Callable<FilePartETag> work;
        while ((work = dispatcher.getWork(buffer)) != null) {
            FilePartETag result = work.call();
            log.debug("Received response for part number: {}", result.PartNumber);
            resultsBuilder.add(result);
        }
        return resultsBuilder.build();
    }


    private static class FilePartETag {
        // Server expects JSON keys to have this particular capitalization.
        public final int PartNumber;
        public final String ETag;

        public FilePartETag(int partNumber, String eTag) {
            PartNumber = partNumber;
            ETag = eTag;
        }
    }


    private static class RetryingHttpClient implements AutoCloseable {

        private final CloseableHttpClient _client;

        private final RetryTemplate _retryTemplate;

        public RetryingHttpClient(PropertiesUtil propertiesUtil) {
            int threadCount = propertiesUtil.getNginxStorageUploadThreadCount();
            _client = HttpClients.custom()
                    .setMaxConnTotal(threadCount)
                    .setMaxConnPerRoute(threadCount)
                    .build();

            ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
            backOffPolicy.setInitialInterval(500);
            backOffPolicy.setMultiplier(2);

            int retryCount = Math.max(0, propertiesUtil.getHttpStorageUploadRetryCount());
            SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
            retryPolicy.setMaxAttempts(1 + retryCount);

            _retryTemplate = new RetryTemplate();
            _retryTemplate.setRetryPolicy(retryPolicy);
            _retryTemplate.setBackOffPolicy(backOffPolicy);
        }

        public HttpResponseWrapper execute(HttpUriRequest request) throws IOException {
            return _retryTemplate.execute(ctx -> tryExecute(request));
        }

        private HttpResponseWrapper tryExecute(HttpUriRequest request) throws IOException {
            HttpResponseWrapper response = new HttpResponseWrapper(_client.execute(request));

            StatusLine status = response.getStatusLine();
            if (HttpStatus.valueOf(status.getStatusCode()).is2xxSuccessful()) {
                return response;
            }
            response.close();
            throw new IOException("HTTP request for " + request.getURI() + " failed with status: " + status);
        }


        @Override
        public void close() {
            IoUtils.closeQuietly(_client);
        }
    }


    private static class HttpResponseWrapper implements AutoCloseable {
        private final CloseableHttpResponse _httpResponse;

        public HttpResponseWrapper(CloseableHttpResponse httpResponse) {
            _httpResponse = httpResponse;
        }


        @Override
        public void close() {
            try (CloseableHttpResponse response = _httpResponse) {
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return;
                }
                // Not closing this prevents connection from being reused by HTTP keep alive.
                IoUtils.closeQuietly(entity.getContent());
            }
            catch (UnsupportedOperationException ignored) {
                // Can be thrown by entity.getContent()
            }
            catch (IOException e) {
                log.warn("Ignored an error occurred while trying to close an HTTP response.", e);
            }
        }


        public HttpEntity getEntity() {
            return _httpResponse.getEntity();
        }

        public Header getFirstHeader(String name) {
            return _httpResponse.getFirstHeader(name);
        }

        public StatusLine getStatusLine() {
            return _httpResponse.getStatusLine();
        }
    }
}

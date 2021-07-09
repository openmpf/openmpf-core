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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.http.client.utils.URIBuilder;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.wfm.camel.operations.detection.artifactextraction.ArtifactExtractionRequest;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.enums.IssueCodes;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.mitre.mpf.wfm.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

@Service
public class S3StorageBackend implements StorageBackend {

    private static final Logger LOG = LoggerFactory.getLogger(S3StorageBackend.class);

    private final PropertiesUtil _propertiesUtil;

    private final LocalStorageBackend _localStorageBackend;

    private final InProgressBatchJobsService _inProgressJobs;

    private final AggregateJobPropertiesUtil _aggregateJobPropertiesUtil;

    @Inject
    public S3StorageBackend(PropertiesUtil propertiesUtil,
                            LocalStorageBackend localStorageBackend,
                            InProgressBatchJobsService inProgressBatchJobsService,
                            AggregateJobPropertiesUtil aggregateJobPropertiesUtil) {
        _propertiesUtil = propertiesUtil;
        _localStorageBackend = localStorageBackend;
        _inProgressJobs = inProgressBatchJobsService;
        _aggregateJobPropertiesUtil = aggregateJobPropertiesUtil;
    }


    @Override
    public boolean canStore(JsonOutputObject outputObject) throws StorageException {
        BatchJob job = _inProgressJobs.getJob(outputObject.getJobId());
        return requiresS3ResultUpload(job.getJobProperties()::get);
    }

    @Override
    public URI store(JsonOutputObject outputObject, Mutable<String> outputSha) throws StorageException, IOException {
        URI localUri = _localStorageBackend.store(outputObject, outputSha);
        BatchJob job = _inProgressJobs.getJob(outputObject.getJobId());
        if (outputSha.getValue() == null) {
            outputSha.setValue(hashExistingFile(Paths.get(localUri)));
        }
        return putInS3IfAbsent(Paths.get(localUri), outputSha.getValue(),
                               job.getJobProperties()::get);
    }


    @Override
    public boolean canStore(ArtifactExtractionRequest request) throws StorageException {
        BatchJob job = _inProgressJobs.getJob(request.getJobId());
        Media media = job.getMedia(request.getMediaId());
        Function<String, String> combinedProperties = _aggregateJobPropertiesUtil.getCombinedProperties(job, media);
        return requiresS3ResultUpload(combinedProperties);
    }


    @Override
    public Table<Integer, Integer, URI> storeArtifacts(ArtifactExtractionRequest request) throws IOException {
        BatchJob job = _inProgressJobs.getJob(request.getJobId());
        Media media = job.getMedia(request.getMediaId());
        Function<String, String> combinedProperties = _aggregateJobPropertiesUtil.getCombinedProperties(job, media);

        Table<Integer, Integer, URI> localResults = _localStorageBackend.storeArtifacts(request);
        var futures = createArtifactUploadFutures(localResults, combinedProperties);
        Table<Integer, Integer, URI> remoteResults = HashBasedTable.create();
        for (var cell : futures.cellSet()) {
            URI resultUri;
            try {
                resultUri = cell.getValue().join();
            }
            catch (CompletionException e) {
                _inProgressJobs.addWarning(
                        request.getJobId(), request.getMediaId(), IssueCodes.REMOTE_STORAGE_UPLOAD,
                        "Artifact stored locally due to: " + e.getCause().getMessage());

                resultUri = localResults.get(cell.getRowKey(), cell.getColumnKey());
            }
            remoteResults.put(cell.getRowKey(), cell.getColumnKey(), resultUri);
        }
        return remoteResults;
    }

    private Table<Integer, Integer, CompletableFuture<URI>> createArtifactUploadFutures(
            Table<Integer, Integer, URI> localResults,
            Function<String, String> combinedProperties) {

        var futures = HashBasedTable.<Integer, Integer, CompletableFuture<URI>>create();
        var semaphore = new Semaphore(Math.max(1, _propertiesUtil.getArtifactParallelUploadCount()));
        for (Table.Cell<Integer, Integer, URI> entry : localResults.cellSet()) {
            try {
                semaphore.acquire();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }

            var future = ThreadUtil.callAsync(
                    () -> putInS3IfAbsent(Path.of(entry.getValue()), combinedProperties));
            future.whenComplete((x, y) -> semaphore.release());
            futures.put(entry.getRowKey(), entry.getColumnKey(), future);
        }
        return futures;
    }


    @Override
    public boolean canStore(MarkupResult markupResult) throws StorageException {
        BatchJob job = _inProgressJobs.getJob(markupResult.getJobId());
        Action action = job.getPipelineElements().getAction(markupResult.getTaskIndex(),
                                                            markupResult.getActionIndex());
        Media media = job.getMedia(markupResult.getMediaId());
        Function<String, String> combinedProperties
                = _aggregateJobPropertiesUtil.getCombinedProperties(job, media, action);
        return requiresS3ResultUpload(combinedProperties);
    }

    @Override
    public void store(MarkupResult markupResult) throws StorageException, IOException {
        _localStorageBackend.store(markupResult);
        BatchJob job = _inProgressJobs.getJob(markupResult.getJobId());
        Media media = job.getMedia(markupResult.getMediaId());
        Action action = job.getPipelineElements().getAction(markupResult.getTaskIndex(),
                                                            markupResult.getActionIndex());
        Function<String, String> combinedProperties
                = _aggregateJobPropertiesUtil.getCombinedProperties(job, media, action);
        Path markupPath = Paths.get(URI.create(markupResult.getMarkupUri()));

        URI uploadedUri = putInS3IfAbsent(markupPath, combinedProperties);
        markupResult.setMarkupUri(uploadedUri.toString());
    }

    /**
     * Ensures that the S3-related properties are valid.
     * @param properties Properties to validate
     * @throws StorageException when an invalid combination of S3 properties are provided.
     */
    public static void validateS3Properties(Function<String, String> properties) throws StorageException {
        // Both will throw if properties are invalid.
        requiresS3MediaDownload(properties);
        requiresS3ResultUpload(properties);
    }



    public static boolean requiresS3MediaDownload(Function<String, String> properties) throws StorageException {
        boolean uploadOnly = Boolean.parseBoolean(properties.apply(MpfConstants.S3_UPLOAD_ONLY_PROPERTY));
        if (uploadOnly) {
            return false;
        }

        boolean hasAccessKey = StringUtils.isNotBlank(properties.apply(MpfConstants.S3_ACCESS_KEY_PROPERTY));
        boolean hasSecretKey = StringUtils.isNotBlank(properties.apply(MpfConstants.S3_SECRET_KEY_PROPERTY));
        if (hasAccessKey && hasSecretKey) {
            return true;
        }
        if (!hasAccessKey && !hasSecretKey) {
            return false;
        }

        String presentProperty;
        String missingProperty;
        if (hasAccessKey) {
            presentProperty = MpfConstants.S3_ACCESS_KEY_PROPERTY;
            missingProperty = MpfConstants.S3_SECRET_KEY_PROPERTY;
        }
        else {
            presentProperty = MpfConstants.S3_SECRET_KEY_PROPERTY;
            missingProperty = MpfConstants.S3_ACCESS_KEY_PROPERTY;
        }
        throw new StorageException(String.format("The %s property was set, but the %s property was not.",
                                                 presentProperty, missingProperty));
    }



    public static boolean requiresS3ResultUpload(Function<String, String> properties) throws StorageException {
        if (StringUtils.isBlank(properties.apply(MpfConstants.S3_RESULTS_BUCKET_PROPERTY))) {
            return false;
        }
        boolean hasAccessKey = StringUtils.isNotBlank(properties.apply(MpfConstants.S3_ACCESS_KEY_PROPERTY));
        boolean hasSecretKey = StringUtils.isNotBlank(properties.apply(MpfConstants.S3_SECRET_KEY_PROPERTY));
        if (hasAccessKey && hasSecretKey) {
            return true;
        }

        if (!hasAccessKey && !hasSecretKey) {
            throw new StorageException(String.format(
                    "The %s property was set, but the %s and %s properties were not.",
                    MpfConstants.S3_RESULTS_BUCKET_PROPERTY, MpfConstants.S3_ACCESS_KEY_PROPERTY,
                    MpfConstants.S3_SECRET_KEY_PROPERTY));
        }

        String presentProperty;
        String missingProperty;
        if (hasAccessKey) {
            presentProperty = MpfConstants.S3_ACCESS_KEY_PROPERTY;
            missingProperty = MpfConstants.S3_SECRET_KEY_PROPERTY;
        }
        else {
            presentProperty = MpfConstants.S3_SECRET_KEY_PROPERTY;
            missingProperty = MpfConstants.S3_ACCESS_KEY_PROPERTY;
        }
        throw new StorageException(String.format(
                "The %s and %s properties were set, but the %s property was not.",
                MpfConstants.S3_RESULTS_BUCKET_PROPERTY, presentProperty, missingProperty));
    }


    public void downloadFromS3(Media media, Function<String, String> combinedProperties)
            throws StorageException {
        try {
            AmazonS3 s3Client = getS3DownloadClient(media.getUri(), combinedProperties);
            String[] pathParts = splitBucketAndObjectKey(media.getUri());
            String bucket = pathParts[0];
            String objectKey = pathParts[1];
            s3Client.getObject(new GetObjectRequest(bucket, objectKey), media.getLocalPath().toFile());
        }
        catch (SdkClientException e) {
            throw new StorageException(String.format("Failed to download \"%s\" due to %s", media.getUri(), e),
                                       e);
        }
    }


    public S3Object getFromS3(String uri, Function<String, String> properties) throws StorageException {
        try {
            AmazonS3 s3Client = getS3DownloadClient(uri, properties);
            String[] pathParts = splitBucketAndObjectKey(uri);
            String bucket = pathParts[0];
            String objectKey = pathParts[1];
            return s3Client.getObject(bucket, objectKey);
        }
        catch (SdkClientException e) {
            throw new StorageException(String.format("Failed to download \"%s\" due to %s", uri, e),
                                       e);
        }
    }


    private static String[] splitBucketAndObjectKey(String uriStr) throws StorageException {
        URI uri = URI.create(uriStr);
        String uriPath = uri.getPath();
        if (uriPath.startsWith("/")) {
            uriPath = uriPath.substring(1);
        }
        String[] parts = uriPath.split("/", 2);
        if (parts.length != 2 || StringUtils.isBlank(parts[0]) || StringUtils.isBlank(parts[1])) {
            throw new StorageException("Unable to determine bucket name and object key from uri: " + uriStr);
        }
        return parts;
    }

    private URI putInS3IfAbsent(Path path, Function<String, String> properties) throws IOException, StorageException {
        return putInS3IfAbsent(path, hashExistingFile(path), properties);
    }

    private URI putInS3IfAbsent(Path path, String hash, Function<String, String> properties) throws IOException, StorageException {
        String objectName = getObjectName(hash);
        URI bucketUri = URI.create(properties.apply(MpfConstants.S3_RESULTS_BUCKET_PROPERTY));
        String resultsBucket = getResultsBucketName(bucketUri);
        LOG.info("Storing \"{}\" in S3 bucket \"{}\" with object key \"{}\" ...", path, bucketUri, objectName);

        try {
            AmazonS3 s3Client = getS3UploadClient(properties);
            boolean alreadyExists = s3Client.doesObjectExist(resultsBucket, objectName);
            if (alreadyExists) {
                LOG.info("Did not upload \"{}\" to S3 bucket \"{}\" and object key \"{}\" " +
                               "because a file with the same SHA-256 hash was already there.",
                         path, bucketUri, objectName);
            }
            else {
                s3Client.putObject(resultsBucket, objectName, path.toFile());
                LOG.info("Successfully stored \"{}\" in S3 bucket \"{}\" with object key \"{}\".",
                         path, bucketUri, objectName);
            }

            URI objectUri = new URIBuilder(bucketUri)
                    .setPath(bucketUri.getPath() + '/' + objectName)
                    .build();
            Files.delete(path);
            return objectUri;
        }
        catch (SdkClientException e) {
            var errorMsg = String.format("Failed to upload %s due to S3 error: %s", path, e);
            LOG.error(errorMsg, e);
            throw new StorageException(errorMsg, e);
        }
        catch (URISyntaxException e) {
            var errorMsg = "Couldn't build uri: " + e;
            LOG.error(errorMsg, e);
            throw new StorageException(errorMsg, e);
        }
    }


    private static String hashExistingFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return DigestUtils.sha256Hex(is);
        }
    }


    private static String getObjectName(String hash) {
        String firstPair = hash.substring(0, 2);
        String secondPair = hash.substring(2, 4);
        return firstPair + '/' + secondPair + '/' + hash;
    }


    private AmazonS3 getS3DownloadClient(String mediaUri, Function<String, String> properties) throws StorageException {
        String endpoint = getS3Endpoint(mediaUri);
        return getS3Client(endpoint, _propertiesUtil.getRemoteMediaDownloadRetries(), properties);
    }

    private AmazonS3 getS3UploadClient(Function<String, String> properties) throws StorageException {
        String endpoint = getS3Endpoint(properties.apply(MpfConstants.S3_RESULTS_BUCKET_PROPERTY));
        return getS3Client(endpoint, _propertiesUtil.getHttpStorageUploadRetryCount(), properties);

    }

    private static AmazonS3 getS3Client(String endpoint, int retryCount, Function<String, String> properties) {
        AWSCredentials credentials = new BasicAWSCredentials(
                properties.apply(MpfConstants.S3_ACCESS_KEY_PROPERTY),
                properties.apply(MpfConstants.S3_SECRET_KEY_PROPERTY));

        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setMaxErrorRetry(retryCount);

        AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(
                endpoint, Regions.US_EAST_1.name());

        return AmazonS3ClientBuilder
                .standard()
                .withPathStyleAccessEnabled(true)
                .withEndpointConfiguration(endpointConfiguration)
                .withClientConfiguration(clientConfig)
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();
    }


    private static String getS3Endpoint(String uri) throws StorageException {
        try {
            return removePartsAfterHost(uri);
        }
        catch(URISyntaxException e) {
            throw new StorageException(
                    "An error occurred while trying to determine the S3 endpoint: " + e.getMessage(),
                    e);
        }
    }


    private static String removePartsAfterHost(String uri) throws URISyntaxException {
        URI serviceUri = new URIBuilder(uri)
                .setPath("")
                .setFragment(null)
                .removeQuery()
                .build();
        if (serviceUri.getHost() == null) {
            throw new URISyntaxException(serviceUri.toString(), "Missing host");
        }
        return serviceUri.toString();
    }


    private static String getResultsBucketName(URI bucketUri) throws StorageException {
        String path = bucketUri.getPath();
        if (path.length() < 2 || path.charAt(0) != '/') {
            throw new StorageException("Could not determine bucket name from URI: " + bucketUri);
        }
        int slash2Pos = path.indexOf('/', 1);
        return slash2Pos < 0
                ? path.substring(1)
                : path.substring(1, slash2Pos);
    }

}

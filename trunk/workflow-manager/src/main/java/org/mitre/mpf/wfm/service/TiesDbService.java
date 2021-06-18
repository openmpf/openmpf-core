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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.ActionType;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.data.entities.transients.TrackCountEntry;
import org.mitre.mpf.wfm.data.entities.transients.TrackCounter;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.CallbackUtils;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.mitre.mpf.wfm.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Refer to https://github.com/Noblis/ties-lib for more information on the Triage Import Export
 * Schema (TIES). For each piece of media, we create one or more TIES
 * "supplementalDescription (Data Object)" entries in the database, one for each
 * analytic (algorithm) run on the media. In general, a "supplementalDescription" is a kind of TIES
 * "assertion", which is used to represent metadata about the media object. In our case it
 * represents the detection and track information in the OpenMPF JSON output object.
 */
@Component
public class TiesDbService {

    private static final Logger LOG = LoggerFactory.getLogger(TiesDbService.class);

    private final PropertiesUtil _propertiesUtil;

    private final AggregateJobPropertiesUtil _aggregateJobPropertiesUtil;

    private final ObjectMapper _objectMapper;

    private final CallbackUtils _callbackUtils;

    @Inject
    TiesDbService(PropertiesUtil propertiesUtil,
                  AggregateJobPropertiesUtil aggregateJobPropertiesUtil,
                  ObjectMapper objectMapper,
                  CallbackUtils callbackUtils) {
        _propertiesUtil = propertiesUtil;
        _aggregateJobPropertiesUtil = aggregateJobPropertiesUtil;
        _objectMapper = objectMapper;
        _callbackUtils = callbackUtils;
    }


    public CompletableFuture<Void> addAssertions(BatchJob job,
                                                 BatchJobStatusType jobStatus,
                                                 Instant timeCompleted,
                                                 URI outputObjectLocation,
                                                 String outputObjectSha,
                                                 TrackCounter trackCounter) {
        var futures = new ArrayList<CompletableFuture<Void>>();

        for (var media : job.getMedia()) {
            for (int taskIdx = 0; taskIdx < job.getPipelineElements().getTaskCount(); taskIdx++) {
                var task = job.getPipelineElements().getTask(taskIdx);

                for (int actionIdx = 0; actionIdx < task.getActions().size(); actionIdx++) {
                    var action = job.getPipelineElements().getAction(taskIdx, actionIdx);
                    URI tiesDbUri;
                    try {
                        var optTiesDbUrl = getTiesDbUri(job, media, action);
                        if (optTiesDbUrl.isPresent()) {
                            tiesDbUri = optTiesDbUrl.get();
                        }
                        else {
                            continue;
                        }
                    }
                    catch (IllegalStateException e) {
                        handleHttpError(job.getId(), e);
                        continue;
                    }

                    futures.add(addActionAssertion(
                            job,
                            jobStatus,
                            timeCompleted,
                            outputObjectLocation,
                            outputObjectSha,
                            trackCounter.get(media.getId(), taskIdx, actionIdx),
                            tiesDbUri,
                            media,
                            action));
                }
            }
        }
        return ThreadUtil.allOf(futures);
    }


    private CompletableFuture<Void> addActionAssertion(
            BatchJob job,
            BatchJobStatusType jobStatus,
            Instant timeCompleted,
            URI outputObjectLocation,
            String outputObjectSha,
            TrackCountEntry trackCountEntry,
            URI tiesDbUrl,
            Media media,
            Action action) {

        var algo = job.getPipelineElements().getAlgorithm(action.getAlgorithm());
        var trackType = algo.getActionType() == ActionType.MARKUP
                ? "MARKUP"
                : trackCountEntry.getTrackType();

        var dataObject = Map.of(
                "algorithm", action.getAlgorithm(),
                "outputType", trackType,
                "jobId", job.getId(),
                "outputUri", outputObjectLocation.toString(),
                "sha256OutputHash", outputObjectSha,
                "processDate", timeCompleted,
                "jobStatus", jobStatus,
                "systemVersion", _propertiesUtil.getSemanticVersion(),
                "systemHostname", getHostName(),
                "trackCount", trackCountEntry.getCount()
        );
        var assertionId = getAssertionId(job.getId(), trackType,
                                         action.getAlgorithm(), timeCompleted);
        var assertion = Map.of(
                "assertionId", assertionId,
                "informationType", "OpenMPF_" + trackType,
                "securityTag", "UNCLASSIFIED",
                "system", "OpenMPF",
                "dataObject", dataObject);

        LOG.info("[Job {}] Posting assertion to TiesDb for the {} action.",
                 job.getId(), action.getName());

        return postAssertion(job.getId(), tiesDbUrl, media.getSha256(), assertion);
    }


    private Optional<URI> getTiesDbUri(BatchJob job, Media media, Action action) {
        var uriString = _aggregateJobPropertiesUtil.getValue("TIES_DB_URL", job, media, action);
        if (uriString == null || uriString.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(uriString));
        }
        catch (URISyntaxException e) {
            throw new IllegalStateException(String.format(
                    "Could not convert TIES_DB_URL=\"%s\" to a URL due to: %s",
                    uriString, e.getMessage()), e);
        }
    }


    private static String getAssertionId(long jobId, String detectionType, String algorithm,
                                         Instant endTime) {
        var digest = DigestUtils.getSha256Digest();
        digest.update(String.valueOf(jobId).getBytes(StandardCharsets.UTF_8));
        digest.update(detectionType.getBytes(StandardCharsets.UTF_8));
        digest.update(algorithm.getBytes(StandardCharsets.UTF_8));
        digest.update(String.valueOf(endTime.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return Hex.encodeHexString(digest.digest());
    }


    private static String getHostName() {
        return Objects.requireNonNullElseGet(
                System.getenv("NODE_HOSTNAME"),
                () -> System.getenv("HOSTNAME"));
    }


    private CompletableFuture<Void> postAssertion(long jobId,
                                                  URI tiesDbUrl,
                                                  String mediaSha,
                                                  Map<String, Object> assertions) {
        try {
            var uri = new URIBuilder(tiesDbUrl)
                    .setPath(tiesDbUrl.getPath() + "/api/db/supplementals")
                    .setParameter("sha256Hash", mediaSha)
                    .build();
            var jsonString = _objectMapper.writeValueAsString(assertions);

            var postRequest = new HttpPost(uri);
            postRequest.addHeader("Content-Type", "application/json");
            RequestConfig requestConfig = RequestConfig.custom()
                    .setSocketTimeout(_propertiesUtil.getHttpCallbackTimeoutMs())
                    .setConnectTimeout(_propertiesUtil.getHttpCallbackTimeoutMs())
                    .build();
            postRequest.setConfig(requestConfig);
            postRequest.setEntity(new StringEntity(jsonString, ContentType.APPLICATION_JSON));

            return _callbackUtils.executeRequest(postRequest,
                                                 _propertiesUtil.getHttpCallbackRetryCount())
                    .thenApply(TiesDbService::checkResponse)
                    .exceptionally(err -> handleHttpError(jobId, err));
        }
        catch (URISyntaxException | JsonProcessingException e) {
            handleHttpError(jobId, e);
            return ThreadUtil.completedFuture(null);
        }
    }


    private static Void checkResponse(HttpResponse response) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 200 && statusCode <= 299) {
            return null;
        }
        try {
            var responseContent = IOUtils.toString(response.getEntity().getContent(),
                                                   StandardCharsets.UTF_8);
            throw new IllegalStateException(String.format(
                    "TiesDb responded with a non-200 status code of %s and body: %s",
                    statusCode, responseContent));
        }
        catch (IOException e) {
            throw new IllegalStateException(
                    "TiesDb responded with a non-200 status code of " + statusCode, e);
        }
    }


    private static Void handleHttpError(long jobId, Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        var warningMessage = String.format(
                "[Job %s] Sending HTTP POST to TiesDb failed due to: %s", jobId, error);
        LOG.warn(warningMessage, error);
        return null;
    }
}

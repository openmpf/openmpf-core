/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2020 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2020 The MITRE Corporation                                       *
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

package org.mitre.mpf.wfm.camel;

import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.mitre.mpf.interop.*;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.ActionType;
import org.mitre.mpf.rest.api.pipelines.Task;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.access.JobRequestDao;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateMarkupResultDaoImpl;
import org.mitre.mpf.wfm.data.entities.persistent.*;
import org.mitre.mpf.wfm.data.entities.transients.Detection;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.enums.*;
import org.mitre.mpf.wfm.event.JobCompleteNotification;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.event.NotificationConsumer;
import org.mitre.mpf.wfm.service.JobStatusBroadcaster;
import org.mitre.mpf.wfm.service.StorageService;
import org.mitre.mpf.wfm.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

@Component(JobCompleteProcessorImpl.REF)
public class JobCompleteProcessorImpl extends WfmProcessor implements JobCompleteProcessor {
    private static final Logger log = LoggerFactory.getLogger(JobCompleteProcessor.class);
    public static final String REF = "jobCompleteProcessorImpl";

    private final Set<NotificationConsumer<JobCompleteNotification>> consumers = new ConcurrentSkipListSet<>();

    @Autowired
    private JobRequestDao jobRequestDao;

    @Autowired
    @Qualifier(HibernateMarkupResultDaoImpl.REF)
    private MarkupResultDao markupResultDao;

    @Autowired
    private PropertiesUtil propertiesUtil;

    @Autowired
    private JsonUtils jsonUtils;

    @Autowired
    private InProgressBatchJobsService inProgressBatchJobs;

    @Autowired
    private JobProgress jobProgressStore;

    @Autowired
    private StorageService storageService;

    @Autowired
    private JobStatusBroadcaster jobStatusBroadcaster;

    @Autowired
    private AggregateJobPropertiesUtil aggregateJobPropertiesUtil;

    @Autowired
    private CallbackUtils callbackUtils;


    @Override
    public void wfmProcess(Exchange exchange) throws WfmProcessingException {
        Long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
        assert jobId != null : String.format("The header '%s' (value=%s) was not set or is not a Long.",
                MpfHeaders.JOB_ID, exchange.getIn().getHeader(MpfHeaders.JOB_ID));

        if(jobId == Long.MIN_VALUE) {
            // If we receive a very large negative Job ID, it means an exception was encountered during processing of a job,
            // and none of the provided error handling logic could fix it. Further processing should not be performed.
            log.warn("[Job {}:*:*] An error prevents a job from completing successfully." +
                    " Please review the logs for additional information.", jobId);
        } else {
            String statusString = exchange.getIn().getHeader(MpfHeaders.JOB_STATUS, String.class);
            Mutable<BatchJobStatusType> jobStatus =
                    new MutableObject<>(BatchJobStatusType.parse(statusString, BatchJobStatusType.UNKNOWN));

            JobRequest jobRequest = jobRequestDao.findById(jobId);
            jobRequest.setTimeCompleted(Instant.now());

            BatchJob job = inProgressBatchJobs.getJob(jobId);
            URI outputObjectUri = null;
            try {
                outputObjectUri = createOutputObject(job, jobRequest.getTimeReceived(),
                        jobRequest.getTimeCompleted(), jobStatus); // this may update the job status
                jobRequest.setOutputObjectPath(outputObjectUri.toString());
                jobRequest.setOutputObjectVersion(propertiesUtil.getOutputObjectVersion());
            } catch (Exception exception) {
                log.error(String.format("Failed to create the output object for job %d due to an exception.", jobId),
                          exception);
                inProgressBatchJobs.addJobError(jobId, IssueCodes.OTHER,
                                                "Failed to create the output object due to: " + exception);
                jobStatus.setValue(BatchJobStatusType.ERROR);
            }

            inProgressBatchJobs.setJobStatus(jobId, jobStatus.getValue());
            jobRequest.setStatus(jobStatus.getValue());
            jobRequest.setJob(jsonUtils.serialize(job));
            jobRequestDao.persist(jobRequest);

            IoUtils.deleteEmptyDirectoriesRecursively(propertiesUtil.getJobMarkupDirectory(jobId).toPath());
            IoUtils.deleteEmptyDirectoriesRecursively(propertiesUtil.getJobArtifactsDirectory(jobId).toPath());
            IoUtils.deleteEmptyDirectoriesRecursively(propertiesUtil.getJobOutputObjectsDirectory(jobId).toPath());

            try {
                jmsUtils.destroyCancellationRoutes(jobId);
            }
            catch (Exception exception) {
                log.warn(String.format("Failed to destroy the cancellation routes associated with job %d." +
                                " If this job is resubmitted, it will likely not complete again!", jobId), exception);
            }

            if (job.getCallbackUrl().isPresent()) {
                sendCallbackAsync(job, outputObjectUri)
                        .whenCompleteAsync((resp, err) -> {
                            if (err != null) { handleFailedCallback(job, jobStatus, err); }
                            completeJob(job, jobStatus);
                        });
            }
            else {
                completeJob(job, jobStatus);
            }
        }
    }

    private void completeJob(BatchJob job, Mutable<BatchJobStatusType> jobStatus) {
        try {
            destroy(job);
        } catch (Exception exception) {
            log.warn(String.format(
                    "Failed to clean up job %d due to an exception. Data for this job will remain in the transient " +
                        "data store, but the status of the job has not been affected by this failure.", job.getId()),
                     exception);
        }

        log.info("Notifying {} completion consumer(s).", consumers.size());
        for (NotificationConsumer<JobCompleteNotification> consumer : consumers) {
            try {
                log.info("Notifying completion consumer {}.", consumer.getId());
                consumer.onNotification(this, new JobCompleteNotification(job.getId()));
            } catch (Exception exception) {
                log.warn(String.format("Completion consumer %s threw an exception.", consumer.getId()), exception);
            }
        }

        jobStatusBroadcaster.broadcast(job.getId(), 100, jobStatus.getValue(), Instant.now());
        jobProgressStore.removeJob(job.getId());
        log.info("[Job {}:*:*] Job complete!", job.getId());
    }


    private void handleFailedCallback(BatchJob job, Mutable<BatchJobStatusType> jobStatus,
                                      Throwable callbackError) {
        if (callbackError instanceof CompletionException && callbackError.getCause() != null) {
            callbackError = callbackError.getCause();
        }

        String callbackMethod = job.getCallbackMethod().orElse("POST");
        String callbackUrl = job.getCallbackUrl()
                // This is will never throw because we already checked that the URL is present.
                .orElseThrow();

        String warningMessage = String.format("Sending HTTP %s callback to %s failed due to: %s",
                                              callbackMethod, callbackUrl, callbackError);
        log.warn(warningMessage, callbackError);
        inProgressBatchJobs.addJobWarning(job.getId(), IssueCodes.FAILED_CALLBACK, warningMessage);

        JobRequest jobRequest = jobRequestDao.findById(job.getId());
        BatchJobStatusType initialStatus = jobStatus.getValue();
        try {
            createOutputObject(job, jobRequest.getTimeReceived(), jobRequest.getTimeCompleted(), jobStatus);
        }
        catch (Exception e) {
            log.warn("Failed to create the output object for Job {} due to an exception.", job.getId(), e);
            jobStatus.setValue(BatchJobStatusType.ERROR);
        }

        if (jobStatus.getValue() != initialStatus) {
            jobRequest.setTimeCompleted(Instant.now());
            jobRequest.setStatus(jobStatus.getValue());
            jobRequestDao.persist(jobRequest);
        }
    }

    private CompletableFuture<HttpResponse> sendCallbackAsync(BatchJob job, URI outputObjectUri) {
        String callbackMethod = job.getCallbackMethod().orElse("POST");

        String callbackUrl = job.getCallbackUrl()
                // This is will never throw because we already checked that the URL is present.
                .orElseThrow();

        log.info("Starting {} callback to {} for job id {}.", callbackMethod, callbackUrl, job.getId());
        try {
            HttpUriRequest request = createCallbackRequest(callbackMethod, callbackUrl,
                                                           job, outputObjectUri);
            return callbackUtils.executeRequest(request, propertiesUtil.getHttpCallbackRetryCount())
                    .thenApply(JobCompleteProcessorImpl::checkResponse);
        }
        catch (Exception e) {
            log.warn(String.format("Failed to issue %s callback to '%s' for job id %s.",
                                   callbackMethod, callbackUrl, job.getId()), e);
            return ThreadUtil.failedFuture(e);
        }
    }


    private static HttpResponse checkResponse(HttpResponse response) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 200 && statusCode <= 299) {
            return response;
        }
        throw new IllegalStateException("The remote server responded with a non-200 status code of: " + statusCode);
    }


    private HttpUriRequest createCallbackRequest(
            String jsonCallbackMethod, String jsonCallbackURL, BatchJob job, URI outputObjectUri)
                throws URISyntaxException {

        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(propertiesUtil.getHttpCallbackTimeoutMs())
                .setConnectTimeout(propertiesUtil.getHttpCallbackTimeoutMs())
                .build();

        if ("GET".equals(jsonCallbackMethod)) {
            URIBuilder callbackUriWithParamsBuilder = new URIBuilder(jsonCallbackURL)
                    .setParameter("jobid", String.valueOf(job.getId()));

            job.getExternalId()
                    .ifPresent(id -> callbackUriWithParamsBuilder.setParameter("externalid", id));

            if (outputObjectUri != null) {
                callbackUriWithParamsBuilder.setParameter("outputobjecturi", outputObjectUri.toString());
            }
            var getRequest = new HttpGet(callbackUriWithParamsBuilder.build());
            getRequest.setConfig(requestConfig);
            return getRequest;
        }

        var postRequest = new HttpPost(jsonCallbackURL);
        postRequest.addHeader("Content-Type", "application/json");
        postRequest.setConfig(requestConfig);

        String outputObjectUriString = outputObjectUri == null
                ? null
                : outputObjectUri.toString();

        JsonCallbackBody jsonBody = new JsonCallbackBody(
                job.getId(), job.getExternalId().orElse(null), outputObjectUriString);

        postRequest.setEntity(new StringEntity(jsonUtils.serializeAsTextString(jsonBody),
                                               ContentType.APPLICATION_JSON));
        return postRequest;
    }


    @Override
    public URI createOutputObject(BatchJob job, Instant timeReceived, Instant timeCompleted,
                                  Mutable<BatchJobStatusType> jobStatus) throws IOException {
        long jobId = job.getId();

        if (job.isCancelled()) {
            jobStatus.setValue(BatchJobStatusType.CANCELLED);
        }

        JsonOutputObject jsonOutputObject = new JsonOutputObject(
                jobId,
                UUID.randomUUID().toString(),
                jsonUtils.convert(job.getPipelineElements()),
                job.getPriority(),
                propertiesUtil.getSiteId(),
                job.getExternalId().orElse(null),
                timeReceived,
                timeCompleted,
                jobStatus.getValue().toString());

        if (job.getJobProperties() != null) {
            jsonOutputObject.getJobProperties().putAll(job.getJobProperties());
        }

        if (job.getOverriddenAlgorithmProperties() != null) {
            jsonOutputObject.getAlgorithmProperties().putAll(job.getOverriddenAlgorithmProperties());
        }
        job.getWarnings().forEach(jsonOutputObject::addWarnings);
        job.getErrors().forEach(jsonOutputObject::addErrors);
        inProgressBatchJobs.getMergedDetectionErrors(job.getId())
                .asMap()
                .forEach(jsonOutputObject::addErrors);

        int mediaIndex = 0;
        for (Media media : job.getMedia()) {
            StringBuilder stateKeyBuilder = new StringBuilder("+");

            JsonMediaOutputObject mediaOutputObject = new JsonMediaOutputObject(
                    media.getId(), media.getUri(), media.getType(),
                    media.getLength(), media.getSha256(), media.isFailed() ? "ERROR" : "COMPLETE");

            mediaOutputObject.getMediaMetadata().putAll(media.getMetadata());
            mediaOutputObject.getMediaProperties().putAll(media.getMediaSpecificProperties());

            MarkupResult markupResult = markupResultDao.findByJobIdAndMediaIndex(jobId, mediaIndex);
            if(markupResult != null) {
                mediaOutputObject.setMarkupResult(new JsonMarkupOutputObject(markupResult.getId(),
                        markupResult.getMarkupUri(), markupResult.getMarkupStatus().name(), markupResult.getMessage()));
            }


            Set<Integer> suppressedTasks = getSuppressedTasks(media, job);

            for (int taskIndex = 0; taskIndex < job.getPipelineElements().getTaskCount(); taskIndex++) {
                Task task = job.getPipelineElements().getTask(taskIndex);
                for (int actionIndex = 0; actionIndex < task.getActions().size(); actionIndex++) {
                    Action action = job.getPipelineElements().getAction(taskIndex, actionIndex);
                    String stateKey = String.format("%s#%s", stateKeyBuilder.toString(), action.getName());

                    for (DetectionProcessingError detectionProcessingError : getDetectionProcessingErrors(
                             job, media.getId(), taskIndex, actionIndex)) {
                        JsonDetectionProcessingError jsonDetectionProcessingError =
                                 new JsonDetectionProcessingError(
                                         detectionProcessingError.getStartFrame(),
                                         detectionProcessingError.getStopFrame(),
                                         detectionProcessingError.getStartTime(),
                                         detectionProcessingError.getStopTime(),
                                         detectionProcessingError.getErrorCode(),
                                         detectionProcessingError.getErrorMessage());
                        if (!mediaOutputObject.getDetectionProcessingErrors().containsKey(stateKey)) {
                            mediaOutputObject.getDetectionProcessingErrors().put(stateKey, new TreeSet<>());
                        }
                        mediaOutputObject.getDetectionProcessingErrors().get(stateKey).add(jsonDetectionProcessingError);
                        if (!StringUtils.equalsIgnoreCase(mediaOutputObject.getStatus(), "COMPLETE")) {
                            mediaOutputObject.setStatus("INCOMPLETE");
                        }
                    }

                    Collection<Track> tracks = inProgressBatchJobs.getTracks(jobId, media.getId(),
                                                                             taskIndex, actionIndex);
                    if(tracks.isEmpty()) {
                        // Always include detection actions in the output object, even if they do not generate any results.
                        addMissingTrackInfo(JsonActionOutputObject.NO_TRACKS_TYPE, stateKey, mediaOutputObject);
                    }
                    else if (suppressedTasks.contains(taskIndex)) {
                        addMissingTrackInfo(JsonActionOutputObject.TRACKS_SUPPRESSED_TYPE, stateKey,
                                            mediaOutputObject);
                    }
                    else {
                        for (Track track : tracks) {
                            JsonTrackOutputObject jsonTrackOutputObject
                                    = createTrackOutputObject(track, stateKey, action, media,
                                                              job);

                            String type = jsonTrackOutputObject.getType();
                            if (!mediaOutputObject.getTypes().containsKey(type)) {
                                mediaOutputObject.getTypes().put(type, new TreeSet<>());
                            }

                            SortedSet<JsonActionOutputObject> actionSet = mediaOutputObject.getTypes().get(type);
                            boolean stateFound = false;
                            for (JsonActionOutputObject jsonAction : actionSet) {
                                if (stateKey.equals(jsonAction.getSource())) {
                                    stateFound = true;
                                    jsonAction.getTracks().add(jsonTrackOutputObject);
                                    break;
                                }
                            }
                            if (!stateFound) {
                                JsonActionOutputObject jsonAction = new JsonActionOutputObject(stateKey);
                                actionSet.add(jsonAction);
                                jsonAction.getTracks().add(jsonTrackOutputObject);
                            }
                        }
                    }

                    if (actionIndex == task.getActions().size() - 1) {
                        stateKeyBuilder.append('#').append(action.getName());
                    }
                }
            }
            jsonOutputObject.getMedia().add(mediaOutputObject);
            mediaIndex++;
        }

        JobStatusCalculator.checkErrorMessages(jsonOutputObject, jobStatus);

        return storageService.store(jsonOutputObject, jobStatus); // this may update the job status
    }


    private static List<DetectionProcessingError> getDetectionProcessingErrors(
        BatchJob job, long mediaId, int taskIndex, int actionIndex) {
        return job.getDetectionProcessingErrors()
                .stream()
                .filter(d -> d.getMediaId() == mediaId && d.getTaskIndex() == taskIndex
                        && d.getActionIndex() == actionIndex)
                .collect(toList());
    }


    private JsonTrackOutputObject createTrackOutputObject(Track track, String stateKey,
                                                          Action action,
                                                          Media media,
                                                          BatchJob job) {
        JsonDetectionOutputObject exemplar = createDetectionOutputObject(track.getExemplar());

        String artifactsAndExemplarsOnlyProp = aggregateJobPropertiesUtil.getValue(
            MpfConstants.OUTPUT_ARTIFACTS_AND_EXEMPLARS_ONLY_PROPERTY, job, media, action);
        boolean artifactsAndExemplarsOnly = Boolean.parseBoolean(artifactsAndExemplarsOnlyProp);

        List<JsonDetectionOutputObject> detections;
        if (artifactsAndExemplarsOnly) {
            // This property requires that the exemplar AND all extracted frames be
            // available in the output object. Otherwise, the user might never know that
            // there were other artifacts extracted.
            detections = track.getDetections().stream()
                         .filter(d -> (d.getArtifactExtractionStatus() == ArtifactExtractionStatus.COMPLETED))
                         .map(d -> createDetectionOutputObject(d))
                         .collect(toList());
            detections.add(exemplar);
        }
        else {
            detections = track.getDetections().stream()
                         .map(JobCompleteProcessorImpl::createDetectionOutputObject)
                         .collect(toList());
        }

        return new JsonTrackOutputObject(
            TextUtils.getTrackUuid(media.getSha256(),
                                   track.getExemplar().getMediaOffsetFrame(),
                                   track.getExemplar().getX(),
                                   track.getExemplar().getY(),
                                   track.getExemplar().getWidth(),
                                   track.getExemplar().getHeight(),
                                   track.getType()),
            track.getStartOffsetFrameInclusive(),
            track.getEndOffsetFrameInclusive(),
            track.getStartOffsetTimeInclusive(),
            track.getEndOffsetTimeInclusive(),
            track.getType(),
            stateKey,
            track.getConfidence(),
            track.getTrackProperties(),
            exemplar,
            detections);
    }


    private static JsonDetectionOutputObject createDetectionOutputObject(Detection detection) {
        return new JsonDetectionOutputObject(
            detection.getX(),
            detection.getY(),
            detection.getWidth(),
            detection.getHeight(),
            detection.getConfidence(),
            detection.getDetectionProperties(),
            detection.getMediaOffsetFrame(),
            detection.getMediaOffsetTime(),
            detection.getArtifactExtractionStatus().name(),
            detection.getArtifactPath());
    }


    private boolean isOutputLastTaskOnly(Media media, BatchJob job) {
        // Action properties and algorithm properties are not checked because it doesn't make sense to apply
        // OUTPUT_LAST_TASK_ONLY to a single task.
        return Boolean.parseBoolean(aggregateJobPropertiesUtil.getValue(MpfConstants.OUTPUT_LAST_TASK_ONLY_PROPERTY, job, media));
    }


    private Set<Integer> getSuppressedTasks(Media media, BatchJob job) {
        if (!isOutputLastTaskOnly(media, job)) {
            return Set.of();
        }

        List<String> taskNames = job.getPipelineElements().getPipeline().getTasks();
        int lastDetectionTask = 0;
        for (int i = taskNames.size() - 1; i >= 0; i--) {
            ActionType actionType = job.getPipelineElements().getAlgorithm(i, 0).getActionType();
            if (actionType == ActionType.DETECTION) {
                lastDetectionTask = i;
                break;
            }
        }
        return IntStream.range(0, lastDetectionTask)
                .boxed()
                .collect(toSet());
    }


    private static void addMissingTrackInfo(String missingTrackKey, String stateKey,
                                            JsonMediaOutputObject mediaOutputObject) {
        Set<JsonActionOutputObject> trackSet = mediaOutputObject.getTypes().computeIfAbsent(
            missingTrackKey, k -> new TreeSet<>());
        boolean stateMissing = trackSet.stream().noneMatch(a -> stateKey.equals(a.getSource()));
        if (stateMissing) {
            trackSet.add(new JsonActionOutputObject(stateKey));
        }
    }


    @Autowired
    private JmsUtils jmsUtils;

    private void destroy(BatchJob batchJob) throws WfmProcessingException {
        for(Media media : batchJob.getMedia()) {
            if(media.getUriScheme().isRemote() && media.getLocalPath() != null) {
                try {
                    Files.deleteIfExists(media.getLocalPath());
                } catch(IOException exception) {
                    log.warn("[{}|*|*] Failed to delete locally cached file '{}' due to an exception. This file must be manually deleted.",
                             batchJob.getId(), media.getLocalPath());
                }
            }
        }
        inProgressBatchJobs.clearJob(batchJob.getId());
    }

    @Override
    public void subscribe(NotificationConsumer<JobCompleteNotification> consumer) {
        log.info("Subscribing completion consumer {}.", consumer.getId());
        consumers.add(consumer);
    }

    @Override
    public void unsubscribe(NotificationConsumer<JobCompleteNotification> consumer) {
        log.info("Unsubscribing completion consumer {}.", consumer.getId());
        consumers.remove(consumer);
    }
}

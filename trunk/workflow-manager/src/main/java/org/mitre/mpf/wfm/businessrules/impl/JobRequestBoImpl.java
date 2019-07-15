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

package org.mitre.mpf.wfm.businessrules.impl;

import org.apache.camel.EndpointInject;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.mitre.mpf.interop.JsonJobRequest;
import org.mitre.mpf.interop.JsonMediaInputObject;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.businessrules.JobRequestBo;
import org.mitre.mpf.wfm.camel.routes.JobCreatorRouteBuilder;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateDao;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateJobRequestDaoImpl;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateMarkupResultDaoImpl;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.service.JobStatusBroadcaster;
import org.mitre.mpf.wfm.service.PipelineService;
import org.mitre.mpf.wfm.util.JmsUtils;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.mitre.mpf.wfm.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JobRequestBoImpl implements JobRequestBo {
    /**
     * Internal enumeration which is used to indicate whether a resubmitted job should use the original
     * priority value or a new value which has been provided.
     */
    private static enum PriorityPolicy {
        /**
         * Resubmits the job with a different priority.
         */
        PROVIDED,

        /**
         * DEFAULT: Resubmits the job using the same priority as its first run.
         */
        EXISTING;

        /**
         * The default action is to re-use the original priority.
         */
        public static final PriorityPolicy DEFAULT = EXISTING;
    }

    private static final Logger log = LoggerFactory.getLogger(JobRequestBoImpl.class);
    public static final String REF = "jobRequestBoImpl";

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private PropertiesUtil propertiesUtil;

    @Autowired
    private JsonUtils jsonUtils;

    @Autowired
    private JmsUtils jmsUtils;

    @Autowired
    private InProgressBatchJobsService inProgressJobs;

    @Autowired
    @Qualifier(HibernateJobRequestDaoImpl.REF)
    private HibernateDao<JobRequest> jobRequestDao;

    @Autowired
    @Qualifier(HibernateMarkupResultDaoImpl.REF)
    private MarkupResultDao markupResultDao;

    @Autowired
    private JobStatusBroadcaster jobStatusBroadcaster;

    @EndpointInject(uri = JobCreatorRouteBuilder.ENTRY_POINT)
    private ProducerTemplate jobRequestProducerTemplate;

    /**
     * Will create and initialize a JSON representation of a job request given the raw parameters.
     * This version of the method does not allow for a callback to be defined.
     *
     * @param externalId
     * @param pipelineName
     * @param media
     * @param algorithmProperties
     * @param jobProperties
     * @param buildOutput
     * @param priority
     * @return
     */
    @Override
    public JsonJobRequest createRequest(String externalId, String pipelineName, List<JsonMediaInputObject> media,
                                        Map<String, Map<String, String>> algorithmProperties,
                                        Map<String, String> jobProperties, boolean buildOutput, int priority) {

        JsonJobRequest jsonJobRequest = new JsonJobRequest(TextUtils.trim(externalId), buildOutput, pipelineService.createJsonPipeline(pipelineName), priority);
        if (media != null) {
            jsonJobRequest.getMedia().addAll(media);
        }

        // update to add the job algorithm-specific-properties, supporting the priority:
        // action-property defaults (lowest) -> action-properties -> job-properties -> algorithm-properties -> media-properties (highest)
        if (algorithmProperties != null) {
            for (Map.Entry<String, Map<String, String>> algorithm : algorithmProperties.entrySet()) {
                Map<String, String> properties = algorithm.getValue();
                Map<String, String> cleanedProperties = new LinkedHashMap<>();
                for (Map.Entry<String, String> property : properties.entrySet()) {
                    cleanedProperties.put(TextUtils.trim(property.getKey()), property.getValue());
                }
                jsonJobRequest.getAlgorithmProperties().put(TextUtils.trim(algorithm.getKey().toUpperCase()),
                        cleanedProperties);
            }
        }

        if (jobProperties != null) {
            for (Map.Entry<String, String> property : jobProperties.entrySet()) {
                jsonJobRequest.getJobProperties().put(property.getKey().toUpperCase(), property.getValue());
            }
        }
        return jsonJobRequest;
    }

    /**
     * Will create and initialize a JSON representation of a job request given the raw parameters.
     * This version of the method allows for a callback to be defined.
     *
     * @param externalId
     * @param pipelineName
     * @param media
     * @param algorithmProperties
     * @param jobProperties
     * @param buildOutput
     * @param priority
     * @param callbackURL
     * @param callbackMethod
     * @return JSON representation of the job request
     */
    @Override
    public JsonJobRequest createRequest(String externalId, String pipelineName, List<JsonMediaInputObject> media, Map<String, Map<String, String>> algorithmProperties, Map<String, String> jobProperties, boolean buildOutput, int priority, String callbackURL, String callbackMethod) {
        log.debug("[createRequest] externalId:" + externalId + " pipeline:" + pipelineName + " buildOutput:" + buildOutput + " priority:" + priority + " callbackURL:" + callbackURL + " callbackMethod:" + callbackMethod);
        String jsonCallbackURL = "";
        String jsonCallbackMethod = "GET";
        if (callbackURL != null && TextUtils.trim(callbackURL) != null) {
            jsonCallbackURL = TextUtils.trim(callbackURL);
        }
        if (callbackMethod != null && TextUtils.trim(callbackMethod) != null && !TextUtils.trim(callbackMethod).equals("GET")) {//only GET or POST allowed
            jsonCallbackMethod = "POST";
        }

        JsonJobRequest jsonJobRequest = new JsonJobRequest(TextUtils.trim(externalId), buildOutput, pipelineService.createJsonPipeline(pipelineName), priority, jsonCallbackURL, jsonCallbackMethod);
        if (media != null) {
            jsonJobRequest.getMedia().addAll(media);
        }

        // Putting algorithm properties in here supports the priority scheme (from lowest to highest):
        // action-property defaults (lowest) -> action-properties -> job-properties -> algorithm-properties -> media-properties (highest)
        if (algorithmProperties != null) {
            for (Map.Entry<String, Map<String, String>> property : algorithmProperties.entrySet()) {
                jsonJobRequest.getAlgorithmProperties().put(property.getKey().toUpperCase(), property.getValue());
            }
        }

        if (jobProperties != null) {
            for (Map.Entry<String, String> property : jobProperties.entrySet()) {
                jsonJobRequest.getJobProperties().put(property.getKey().toUpperCase(), property.getValue());
            }
        }
        return jsonJobRequest;
    }

    /**
     * Will create a new JobRequest using the provided JSON job request and persist it in the database for long-term storage
     * and will send the job request to the components using the ActiveMQ routes.
     * Upon return, the job will be persisted in the long-term database.
     *
     * @param jobRequest JSON representation of the job request
     * @return initialized job request
     * @throws WfmProcessingException
     */
    @Override
    public JobRequest run(JsonJobRequest jobRequest) throws WfmProcessingException {
        JobRequest jobRequestEntity = initialize(jobRequest);
        return runInternal(jobRequestEntity, jobRequest, (jobRequest == null) ? propertiesUtil.getJmsPriority() : jobRequest.getPriority());
    }

    /**
     * Will create a new JobRequest using the provided JSON job request and persist it in the database for long-term storage.
     * Upon return, the job will be persisted in the long-term database.
     *
     * @param jsonJobRequest JSON representation of the job request
     * @return initialized job request
     * @throws WfmProcessingException
     */
    @Override
    public JobRequest initialize(JsonJobRequest jsonJobRequest) throws WfmProcessingException {
        JobRequest jobRequestEntity = new JobRequest();
        return initializeInternal(jobRequestEntity, jsonJobRequest);
    }

    @Override
    public JobRequest resubmit(long jobId) throws WfmProcessingException {
        return resubmitInternal(jobId, PriorityPolicy.EXISTING, 0);
    }

    @Override
    public JobRequest resubmit(long jobId, int priority) throws WfmProcessingException {
        return resubmitInternal(jobId, PriorityPolicy.PROVIDED, priority);
    }

    /**
     * Will cancel a batch job.
     * This method will mark the job as cancelled in both the TransientJob and in the long-term database.
     * The job cancel request will also be sent along to the components via ActiveMQ using the
     * JobCreatorRouteBuilder.ENTRY_POINT.
     *
     * @param jobId
     * @return true if the job was successfully cancelled, false otherwise
     * @throws WfmProcessingException
     */
    @Override
    public synchronized boolean cancel(long jobId) throws WfmProcessingException {
        log.debug("[Job {}:*:*] Received request to cancel this job.", jobId);
        JobRequest jobRequest = jobRequestDao.findById(jobId);
        if (jobRequest == null) {
            throw new WfmProcessingException(String.format("A job with the id %d is not known to the system.", jobId));
        }

        if (jobRequest.getStatus() == null) {
            throw new WfmProcessingException(String.format("Job %d must not have a null status.", jobId));
        }

        if (jobRequest.getStatus().isTerminal() || jobRequest.getStatus() == BatchJobStatusType.CANCELLING) {
            log.warn("[Job {}:*:*] This job is in the state of '{}' and cannot be cancelled at this time.", jobId, jobRequest.getStatus().name());
            return false;
        } else {
            log.info("[Job {}:*:*] Cancelling job.", jobId);


            if (inProgressJobs.cancelJob(jobId)) {
                try {
                    // Try to move any pending work items on the queues to the appropriate cancellation queues.
                    // If this operation fails, any remaining pending items will continue to process, but
                    // the future splitters should not create any new work items. In short, if this fails,
                    // the system should not be affected, but the job may not complete any faster.
                    jmsUtils.cancel(jobId);
                } catch (Exception exception) {
                    log.warn("[Job {}:*:*] Failed to remove the pending work elements in the message broker for this job. The job must complete the pending work elements before it will cancel the job.", jobId, exception);
                }
                jobRequest.setStatus(BatchJobStatusType.CANCELLING);
                jobRequestDao.persist(jobRequest);
            } else {
                log.warn("[Job {}:*:*] The job is not in progress and cannot be cancelled at this time.", jobId);
            }
            return true;
        }
    }

    private JobRequest resubmitInternal(long jobId, PriorityPolicy priorityPolicy, int priority) throws WfmProcessingException {
        priorityPolicy = (priorityPolicy == null) ? PriorityPolicy.DEFAULT : priorityPolicy;

        log.debug("Attempting to resubmit job {} using {} priority of {}.", jobId, priorityPolicy.name(), priority);

        JobRequest jobRequest = jobRequestDao.findById(jobId);
        if (jobRequest == null) {
            throw new WfmProcessingException(String.format("A job with id %d is not known to the system.", priority));
        }
        if (!jobRequest.getStatus().isTerminal()) {
            throw new WfmProcessingException(String.format(
                    "The job with id %d is in the non-terminal state of '%s'. Only jobs in a terminal state may be resubmitted.",
                    jobId, jobRequest.getStatus().name()));
        }
        if (pipelineService.getPipeline(jobRequest.getPipeline()) == null) {
            throw new WfmProcessingException(String.format("The \"%s\" pipeline does not exist.",
                                                           jobRequest.getPipeline()));
        }
        JsonJobRequest jsonJobRequest = jsonUtils.deserialize(jobRequest.getInputObject(), JsonJobRequest.class);

        // If the priority should be changed during resubmission, make that change now.
        if (priorityPolicy == PriorityPolicy.PROVIDED) {
            jsonJobRequest.setPriority(priority);
        }

        jobRequest = initializeInternal(jobRequest, jsonJobRequest);
        markupResultDao.deleteByJobId(jobId);
        FileSystemUtils.deleteRecursively(propertiesUtil.getJobArtifactsDirectory(jobId));
        FileSystemUtils.deleteRecursively(propertiesUtil.getJobOutputObjectsDirectory(jobId));
        FileSystemUtils.deleteRecursively(propertiesUtil.getJobMarkupDirectory(jobId));

        return runInternal(jobRequest, jsonJobRequest, priority);
    }

    /**
     * Finish initializing the JobRequest and persist it in the database for long-term storage.
     * Upon return, the job will be persisted in the long-term database.
     *
     * @param jobRequest     partially initialized jobRequest
     * @param jsonJobRequest JSON version of the job request that will be serialized into the jobRequests input object
     * @return fully initialized jobRequest
     * @throws WfmProcessingException
     */
    private JobRequest initializeInternal(JobRequest jobRequest, JsonJobRequest jsonJobRequest) throws WfmProcessingException {
        jobRequest.setPriority(jsonJobRequest.getPriority());
        jobRequest.setStatus(BatchJobStatusType.INITIALIZED);
        jobRequest.setTimeReceived(Instant.now());
        jobRequest.setInputObject(jsonUtils.serialize(jsonJobRequest));
        jobRequest.setPipeline(jsonJobRequest.getPipeline() == null ? null : TextUtils.trimAndUpper(jsonJobRequest.getPipeline().getName()));

        // Reset output object paths.
        jobRequest.setOutputObjectPath(null);

        // Set output object version to null.
        jobRequest.setOutputObjectVersion(null);

        JobRequest persistedRequest = jobRequestDao.persist(jobRequest);

        jobStatusBroadcaster.broadcast(persistedRequest.getId(), 0, BatchJobStatusType.INITIALIZED);

        return persistedRequest;

    }

    /**
     * Send the job request to the components via ActiveMQ using the JobCreatorRouteBuilder.ENTRY_POINT.
     *
     * @param jobRequest
     * @param jsonJobRequest
     * @param priority
     * @return
     * @throws WfmProcessingException
     */
    private JobRequest runInternal(JobRequest jobRequest, JsonJobRequest jsonJobRequest, int priority) throws WfmProcessingException {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put(MpfHeaders.JOB_ID, jobRequest.getId());
        headers.put(MpfHeaders.JMS_PRIORITY, Math.max(0, Math.min(9, priority)));
        log.info("[Job {}|*|*] Job has started and is running at priority {}.", jobRequest.getId(), headers.get(MpfHeaders.JMS_PRIORITY));
        jobRequestProducerTemplate.sendBodyAndHeaders(JobCreatorRouteBuilder.ENTRY_POINT, ExchangePattern.InOnly, jsonUtils.serialize(jsonJobRequest), headers);
        return jobRequest;
    }
}

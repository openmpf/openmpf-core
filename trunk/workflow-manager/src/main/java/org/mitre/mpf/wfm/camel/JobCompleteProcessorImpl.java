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

package org.mitre.mpf.wfm.camel;

import org.apache.camel.Exchange;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.mitre.mpf.interop.*;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateDao;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateJobRequestDaoImpl;
import org.mitre.mpf.wfm.data.access.hibernate.HibernateMarkupResultDaoImpl;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.transients.*;
import org.mitre.mpf.wfm.enums.ActionType;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.mitre.mpf.wfm.enums.MpfHeaders;
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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
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
	@Qualifier(HibernateJobRequestDaoImpl.REF)
	private HibernateDao<JobRequest> jobRequestDao;

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


	@Override
	public void wfmProcess(Exchange exchange) throws WfmProcessingException {
		Long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
		assert jobId != null : String.format("The header '%s' (value=%s) was not set or is not a Long.", MpfHeaders.JOB_ID, exchange.getIn().getHeader(MpfHeaders.JOB_ID));

		if(jobId == Long.MIN_VALUE) {
			// If we receive a very large negative Job ID, it means an exception was encountered during processing of a job,
			// and none of the provided error handling logic could fix it. Further processing should not be performed.
			log.warn("[Job {}:*:*] An error prevents a job from completing successfully. Please review the logs for additional information.", jobId);
		} else {
			String statusString = exchange.getIn().getHeader(MpfHeaders.JOB_STATUS, String.class);
			Mutable<BatchJobStatusType> jobStatus = new MutableObject<>(BatchJobStatusType.parse(statusString, BatchJobStatusType.UNKNOWN));

			TransientJob transientJob = inProgressBatchJobs.getJob(jobId);
			try {
				markJobStatus(jobId, BatchJobStatusType.BUILDING_OUTPUT_OBJECT);

				// NOTE: jobStatus is mutable - it __may__ be modified in createOutputObject!
				createOutputObject(transientJob, jobStatus);
			} catch (Exception exception) {
				log.warn("Failed to create the output object for Job {} due to an exception.", jobId, exception);
				jobStatus.setValue(BatchJobStatusType.ERROR);
			}

			markJobStatus(jobId, jobStatus.getValue());

			try {
				callback(transientJob);
			} catch (Exception exception) {
				log.warn("Failed to make callback (if appropriate) for Job #{}.", jobId);
			}

			// Tear down the job.
			try {
				destroy(transientJob);
			} catch (Exception exception) {
				log.warn("Failed to clean up Job {} due to an exception. Data for this job will remain in the transient data store, but the status of the job has not been affected by this failure.", jobId, exception);
			}

			log.info("Notifying {} completion consumer(s).", consumers.size());
			for (NotificationConsumer<JobCompleteNotification> consumer : consumers) {
				try {
					log.info("Notifying completion consumer {}.", consumer.getId());
					consumer.onNotification(this, new JobCompleteNotification(exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class)));
				} catch (Exception exception) {
					log.warn("Completion consumer {} threw an exception.", consumer.getId(), exception);
				}
			}

			jobStatusBroadcaster.broadcast(jobId, 100, jobStatus.getValue(), Instant.now());
			jobProgressStore.setJobProgress(jobId, 100.0f);
			log.info("[Job {}:*:*] Job complete!", jobId);
		}
	}

	private void callback(TransientJob transientJob) throws WfmProcessingException {
		long jobId = transientJob.getId();
		String jsonCallbackURL = transientJob.getCallbackUrl().orElse(null);
		if (jsonCallbackURL == null) {
			return;
		}
		String jsonCallbackMethod = transientJob.getCallbackMethod()
				.filter(cbm -> cbm.equalsIgnoreCase("POST") || cbm.equalsIgnoreCase("GET"))
				.orElse(null);
		if (jsonCallbackMethod == null) {
			return;
		}


        log.debug("Starting {} callback to {} for job id {}.", jsonCallbackMethod, jsonCallbackURL, jobId);
        try {
            JsonCallbackBody jsonBody = new JsonCallbackBody(jobId, transientJob.getExternalId().orElse(null));
            new Thread(new CallbackThread(jsonCallbackURL, jsonCallbackMethod, jsonBody)).start();
        } catch (IOException ioe) {
            log.warn(String.format("Failed to issue %s callback to '%s' for job id %s.",
                                   jsonCallbackMethod, jsonCallbackURL, jobId), ioe);
        }
	}

	private void markJobStatus(long jobId, BatchJobStatusType jobStatus) {
		log.debug("Marking Job {} as '{}'.", jobId, jobStatus);

		JobRequest jobRequest = jobRequestDao.findById(jobId);
		assert jobRequest != null : String.format("A job request entity must exist with the ID %d", jobId);

		jobRequest.setTimeCompleted(Instant.now());
		jobRequest.setStatus(jobStatus);
		jobRequestDao.persist(jobRequest);
	}

	@Override
	public void createOutputObject(TransientJob transientJob, Mutable<BatchJobStatusType> jobStatus) {
		long jobId = transientJob.getId();
		JobRequest jobRequest = jobRequestDao.findById(jobId);

		if(transientJob.isCancelled()) {
			jobStatus.setValue(BatchJobStatusType.CANCELLED);
		}

		JsonOutputObject jsonOutputObject = new JsonOutputObject(
				jobRequest.getId(),
				UUID.randomUUID().toString(),
				jsonUtils.convert(transientJob.getPipeline()),
				transientJob.getPriority(),
				propertiesUtil.getSiteId(),
				transientJob.getExternalId().orElse(null),
				jobRequest.getTimeReceived(),
				jobRequest.getTimeCompleted(),
				jobStatus.getValue().toString());

		if (transientJob.getOverriddenJobProperties() != null) {
			jsonOutputObject.getJobProperties().putAll(transientJob.getOverriddenJobProperties());
		}

		if (transientJob.getOverriddenAlgorithmProperties() != null) {
			jsonOutputObject.getAlgorithmProperties().putAll(transientJob.getOverriddenAlgorithmProperties().rowMap());
		}
		jsonOutputObject.getJobWarnings().addAll(transientJob.getWarnings());
		jsonOutputObject.getJobErrors().addAll(transientJob.getErrors());


		IoUtils.deleteEmptyDirectoriesRecursively(propertiesUtil.getJobMarkupDirectory(jobId).toPath());

		boolean hasDetectionProcessingError = false;

		int mediaIndex = 0;
		for(TransientMedia transientMedia : transientJob.getMedia()) {
			StringBuilder stateKeyBuilder = new StringBuilder("+");

			JsonMediaOutputObject mediaOutputObject = new JsonMediaOutputObject(transientMedia.getId(), transientMedia.getUri(), transientMedia.getType(),
					transientMedia.getLength(), transientMedia.getSha256(), transientMedia.getMessage(),
					transientMedia.isFailed() ? "ERROR" : "COMPLETE");

			mediaOutputObject.getMediaMetadata().putAll(transientMedia.getMetadata());
			mediaOutputObject.getMediaProperties().putAll(transientMedia.getMediaSpecificProperties());

			MarkupResult markupResult = markupResultDao.findByJobIdAndMediaIndex(jobId, mediaIndex);
			if(markupResult != null) {
				mediaOutputObject.setMarkupResult(new JsonMarkupOutputObject(markupResult.getId(), markupResult.getMarkupUri(), markupResult.getMarkupStatus().name(), markupResult.getMessage()));
			}


			if(transientJob.getPipeline() != null) {
				Set<Integer> suppressedStages = getSuppressedStages(transientMedia, transientJob);

				for (int stageIndex = 0; stageIndex < transientJob.getPipeline().getStages().size(); stageIndex++) {
					TransientStage transientStage = transientJob.getPipeline().getStages().get(stageIndex);
					for (int actionIndex = 0; actionIndex < transientStage.getActions().size(); actionIndex++) {
						TransientAction transientAction = transientStage.getActions().get(actionIndex);
						String stateKey = String.format("%s#%s", stateKeyBuilder.toString(), transientAction.getName());

						for (DetectionProcessingError detectionProcessingError : getDetectionProcessingErrors(transientJob, transientMedia.getId(), stageIndex, actionIndex)) {
							hasDetectionProcessingError = !MpfConstants.REQUEST_CANCELLED.equals(detectionProcessingError.getError());
							JsonDetectionProcessingError jsonDetectionProcessingError = new JsonDetectionProcessingError(detectionProcessingError.getStartOffset(), detectionProcessingError.getEndOffset(), detectionProcessingError.getError());
							if (!mediaOutputObject.getDetectionProcessingErrors().containsKey(stateKey)) {
								mediaOutputObject.getDetectionProcessingErrors().put(stateKey, new TreeSet<>());
							}
							mediaOutputObject.getDetectionProcessingErrors().get(stateKey).add(jsonDetectionProcessingError);
							if (!StringUtils.equalsIgnoreCase(mediaOutputObject.getStatus(), "COMPLETE")) {
								mediaOutputObject.setStatus("INCOMPLETE");
								if (StringUtils.equalsIgnoreCase(jsonOutputObject.getStatus(), "COMPLETE")) {
									jsonOutputObject.setStatus("INCOMPLETE");
								}
							}
						}

						Collection<Track> tracks = inProgressBatchJobs.getTracks(jobId, transientMedia.getId(),
						                                                         stageIndex, actionIndex);
						if(tracks.isEmpty()) {
							// Always include detection actions in the output object, even if they do not generate any results.
							addMissingTrackInfo(JsonActionOutputObject.NO_TRACKS_TYPE, stateKey, mediaOutputObject);
						}
						else if (suppressedStages.contains(stageIndex)) {
							addMissingTrackInfo(JsonActionOutputObject.TRACKS_SUPPRESSED_TYPE, stateKey,
							                    mediaOutputObject);
						}
						else {
							for (Track track : tracks) {
								JsonTrackOutputObject jsonTrackOutputObject
										= createTrackOutputObject(track, stateKey, transientAction, transientMedia,
										                          transientJob);

								String type = jsonTrackOutputObject.getType();
								if (!mediaOutputObject.getTypes().containsKey(type)) {
									mediaOutputObject.getTypes().put(type, new TreeSet<>());
								}

								SortedSet<JsonActionOutputObject> actionSet = mediaOutputObject.getTypes().get(type);
								boolean stateFound = false;
								for (JsonActionOutputObject action : actionSet) {
									if (stateKey.equals(action.getSource())) {
										stateFound = true;
										action.getTracks().add(jsonTrackOutputObject);
										break;
									}
								}
								if (!stateFound) {
									JsonActionOutputObject action = new JsonActionOutputObject(stateKey);
									actionSet.add(action);
									action.getTracks().add(jsonTrackOutputObject);
								}
							}
						}

						if (actionIndex == transientStage.getActions().size() - 1) {
							stateKeyBuilder.append('#').append(transientAction.getName());
						}
					}
				}
			}
			jsonOutputObject.getMedia().add(mediaOutputObject);
			mediaIndex++;
		}

		if (hasDetectionProcessingError) {
			jsonOutputObject.getJobErrors()
					.add("Some components had errors for some media. Refer to the detectionProcessingErrors fields.");
		}

		try {
			URI outputLocation = storageService.store(jsonOutputObject);
			jobRequest.setOutputObjectPath(outputLocation.toString());
			jobRequest.setOutputObjectVersion(propertiesUtil.getOutputObjectVersion());
			checkErrorMessages(jsonOutputObject, jobStatus);
			jobRequestDao.persist(jobRequest);
		} catch(IOException | WfmProcessingException wpe) {
			log.error("Failed to create the JSON detection output object for '{}' due to an exception.", jobId, wpe);
		}

		try {
			jmsUtils.destroyCancellationRoutes(jobId);
		} catch (Exception exception) {
			log.warn("Failed to destroy the cancellation routes associated with {}. If this job is resubmitted, it will likely not complete again!", jobId, exception);
		}
	}


	private static List<DetectionProcessingError> getDetectionProcessingErrors(
			TransientJob job, long mediaId, int stageIndex, int actionIndex) {
		return job.getDetectionProcessingErrors()
				.stream()
				.filter(d -> d.getMediaId() == mediaId && d.getStageIndex() == stageIndex
						&& d.getActionIndex() == actionIndex)
				.collect(toList());
	}


	private static JsonTrackOutputObject createTrackOutputObject(Track track, String stateKey,
	                                                             TransientAction transientAction,
	                                                             TransientMedia transientMedia,
	                                                             TransientJob transientJob) {
		JsonDetectionOutputObject exemplar = createDetectionOutputObject(track.getExemplar());

		AggregateJobPropertiesUtil.PropertyInfo exemplarsOnlyProp = AggregateJobPropertiesUtil.calculateValue(
				MpfConstants.OUTPUT_EXEMPLARS_ONLY_PROPERTY,
				transientAction.getProperties(),
				transientJob.getOverriddenJobProperties(),
				transientAction,
				transientJob.getOverriddenAlgorithmProperties(),
				transientMedia.getMediaSpecificProperties());

		boolean exemplarsOnly;
		if (exemplarsOnlyProp.getLevel() == AggregateJobPropertiesUtil.PropertyLevel.NONE) {
			exemplarsOnly = transientJob.getSystemPropertiesSnapshot().isOutputObjectExemplarOnly();
		}
		else {
			exemplarsOnly = Boolean.valueOf(exemplarsOnlyProp.getValue());
		}

		List<JsonDetectionOutputObject> detections;
		if (exemplarsOnly) {
			detections = Collections.singletonList(exemplar);
		}
		else {
			detections = track.getDetections().stream()
					.map(d -> createDetectionOutputObject(d))
					.collect(toList());
		}

		return new JsonTrackOutputObject(
				TextUtils.getTrackUuid(transientMedia.getSha256(),
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


	private static void checkErrorMessages(JsonOutputObject outputObject, Mutable<BatchJobStatusType> jobStatus) {
		if (jobStatus.getValue() == BatchJobStatusType.COMPLETE_WITH_ERRORS) {
	    	return;
	    }
		if (!outputObject.getJobErrors().isEmpty()) {
			jobStatus.setValue(BatchJobStatusType.COMPLETE_WITH_ERRORS);
			return;
		}
		if (!outputObject.getJobWarnings().isEmpty()) {
			jobStatus.setValue(BatchJobStatusType.COMPLETE_WITH_WARNINGS);
		}
	}


	private static boolean isOutputLastStageOnly(TransientMedia transientMedia, TransientJob transientJob) {
	    // Action properties and algorithm properties are not checked because it doesn't make sense to apply
		// OUTPUT_LAST_STAGE_ONLY to a single stage.
		String mediaProperty = transientMedia.getMediaSpecificProperty(MpfConstants.OUTPUT_LAST_STAGE_ONLY_PROPERTY);
		if (mediaProperty != null) {
			return Boolean.parseBoolean(mediaProperty);
		}

		String jobProperty = transientJob.getOverriddenJobProperties()
				.get(MpfConstants.OUTPUT_LAST_STAGE_ONLY_PROPERTY);
		if (jobProperty != null) {
			return Boolean.parseBoolean(jobProperty);
		}

		return transientJob.getSystemPropertiesSnapshot().isOutputObjectLastStageOnly();
	}


	private static Set<Integer> getSuppressedStages(TransientMedia transientMedia, TransientJob transientJob) {
		if (!isOutputLastStageOnly(transientMedia, transientJob)) {
			return Collections.emptySet();
		}

		List<TransientStage> stages = transientJob.getPipeline().getStages();
		int lastDetectionStage = 0;
		for (int i = stages.size() - 1; i >= 0; i--) {
			TransientStage stage = stages.get(i);
			if (stage.getActionType() == ActionType.DETECTION) {
				lastDetectionStage = i;
				break;
			}
		}
		return IntStream.range(0, lastDetectionStage)
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

	private void destroy(TransientJob transientJob) throws WfmProcessingException {
		for(TransientMedia transientMedia : transientJob.getMedia()) {
			if(transientMedia.getUriScheme().isRemote() && transientMedia.getLocalPath() != null) {
				try {
					Files.deleteIfExists(transientMedia.getLocalPath());
				} catch(Exception exception) {
					log.warn("[{}|*|*] Failed to delete locally cached file '{}' due to an exception. This file must be manually deleted.", transientJob.getId(), transientMedia.getLocalPath());
				}
			}
		}
		inProgressBatchJobs.clearJob(transientJob.getId());
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

	/**
	 * Thread to handle the Callback to a URL given a HTTP method
	 */
	public class CallbackThread implements Runnable {
		private long jobId;
		private String callbackURL;
		private String callbackMethod;
		private HttpUriRequest req;

		public CallbackThread(String callbackURL,String callbackMethod,JsonCallbackBody body) throws UnsupportedEncodingException {
			jobId = body.getJobId();
			this.callbackURL = callbackURL;
			this.callbackMethod = callbackMethod;

			if(callbackMethod.equals("GET")) {
				String jsonCallbackURL2 = callbackURL;
				if(jsonCallbackURL2.contains("?")){
					jsonCallbackURL2 +="&";
				}else{
					jsonCallbackURL2 +="?";
				}
				jsonCallbackURL2 +="jobid="+body.getJobId();
				if(body.getExternalId() != null){
					jsonCallbackURL2 += "&externalid="+body.getExternalId();
				}
				req = new HttpGet(jsonCallbackURL2);
			}else { // this is for a POST
				HttpPost post = new HttpPost(callbackURL);
				post.addHeader("Content-Type", "application/json");
				try {
					post.setEntity(new StringEntity(jsonUtils.serializeAsTextString(body)));
					req = post;
				} catch (WfmProcessingException e) {
					log.error("Cannot serialize CallbackBody for job id " + jobId, e);
				}
			}
		}

		@Override
		public void run() {
			final HttpClient httpClient = HttpClientBuilder.create().build();
			try {
				HttpResponse response = httpClient.execute(req);
				log.info("{} callback issued to '{}' for job id {}. (Response={})",
				         callbackMethod, callbackURL, jobId, response);
			} catch (Exception exception) {
				log.warn(String.format("Failed to issue %s callback to '%s' for job id %s.",
				                       callbackMethod, callbackURL, jobId), exception);
			}
		}
	}
}

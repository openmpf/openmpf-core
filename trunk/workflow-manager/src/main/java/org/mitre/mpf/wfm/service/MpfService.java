/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2016 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2016 The MITRE Corporation                                       *
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

import org.mitre.mpf.interop.JsonJobRequest;
import org.mitre.mpf.interop.JsonMediaInputObject;
import org.mitre.mpf.interop.JsonStreamingJobRequest;
import org.mitre.mpf.interop.JsonStreamingInputObject;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.data.entities.persistent.StreamingJobRequest;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.persistent.SystemMessage;

import java.util.List;
import java.util.Map;

public interface MpfService {

	// ====================
	// Job service methods.
	// ====================

	/**
	 * Create a new batch job which will execute the specified pipeline on the provided list of provided URIs.
	 * @param media A List of <code>JsonMediaInputObject</code> entries, each representing a medium to be processed
	 *              and an optional set of media properties.
	 * @param algorithmProperties A map of properties which will override the job properties on this job for a particular algorithm.
	 * @param jobProperties A map of properties which will override the default and pipeline properties on this job.
	 * @param pipelineName The name of the pipeline to execute.
	 * @param externalId A user-defined and optional external identifier for the job.
	 * @param buildOutput {@literal true} to build output objects, {@literal false} to suppress output objects.
	 * @param priority The priority to assign to this job.
	 * @return A {@link org.mitre.mpf.interop.JsonJobRequest} which summarizes this request.
	 */
	public JsonJobRequest createJob(List<JsonMediaInputObject> media, Map<String,Map> algorithmProperties, Map<String,String> jobProperties, String pipelineName, String externalId, boolean buildOutput, int priority);

	/**
	 * Create a new batch job which will execute the specified pipeline on the provided list of provided URIs.
	 * @param media A List of <code>JsonMediaInputObject</code> entries, each representing a medium to be processed
	 *              and an optional set of media properties.
	 * @param algorithmProperties A map of properties which will override the job properties on this job for a particular algorithm.
	 * @param jobProperties A map of properties which will override the default and pipeline properties on this job.
	 * @param pipelineName The name of the pipeline to execute.
	 * @param externalId A user-defined and optional external identifier for the job.
	 * @param buildOutput {@literal true} to build output objects, {@literal false} to suppress output objects.
	 * @param priority The priority to assign to this job.
	 * @param callback The callback URL which will be notified when the job completes.
	 * @param method The method to communicate the response body to the callback URL.
	 * @return A {@link org.mitre.mpf.interop.JsonJobRequest} which summarizes this request.
	 */
	public JsonJobRequest createJob(List<JsonMediaInputObject> media, Map<String,Map> algorithmProperties, Map<String,String> jobProperties, String pipelineName, String externalId, boolean buildOutput, int priority, String callback, String method);

	/**
	 * Create a new streaming job which will execute the specified pipeline on the URI defined in the stream object.
	 * @param json_stream
	 * @param algorithmProperties
	 * @param jobProperties
	 * @param pipelineName
	 * @param externalId
	 * @param buildOutput
	 * @param priority
	 * @param stallAlertDetectionThreshold
	 * @param stallAlertRate
	 * @param stallTimeout
	 * @param healthReportCallbackURI
	 * @param summaryReportCallbackURI
	 * @param newTrackAlertCallbackURI
	 * @param method
	 * @return A {@link org.mitre.mpf.interop.JsonStreamingJobRequest} which summarizes this request.
	 */
	public JsonStreamingJobRequest createStreamingJob(JsonStreamingInputObject json_stream,
													  Map<String,Map<String,String>> algorithmProperties,
													  Map<String,String> jobProperties,
													  String pipelineName, String externalId,
													  boolean buildOutput, int priority,
													  long stallAlertDetectionThreshold,
													  long stallAlertRate,
													  long stallTimeout,
													  String healthReportCallbackURI,
													  String summaryReportCallbackURI,
													  String newTrackAlertCallbackURI,
													  String method);

	/**
	 * Asynchronously submits a JSON-based batch job request and returns the identifier associated with the persistent job request which was created.
	 * Note: this method creates the jobId for this newly created batch job
	 * @param jobRequest The batch job to execute.
	 * @return The jobId of the batch job which was created.
	 */
	public long submitJob(JsonJobRequest jobRequest);
	/**
	 * Asynchronously submits a JSON-based streaming job request and returns the identifier associated with the persistent job request which was created.
	 * Note: this method creates the jobId for this newly created streaming job
	 * @param streamingJobRequest The streaming job to execute.
	 * @return The jobId of the streaming job which was created.
	 */
	public long submitJob(JsonStreamingJobRequest streamingJobRequest);

	/** Create the output object file system for the specified streaming job and store parameters describing
	 * the output object file system within the persistant object representing this streaming job
	 * @param jobId The unique job id of the streaming job
	 * @return true if successful, false otherwise
	 */
	public boolean initializeOutputDirectoryForStreamingJob(long jobId);

	/**
	 * Asynchronously submits a job using the originally provided priority. See {@link #resubmitJob(long, int)}. */
	long resubmitJob(long jobId);

	/**
	 * Asynchronously resubmits a job. It is assumed that the job 1) exists and 2) is in a terminal state at the
	 * moment the request is made.
	 * @param jobId The MPF-assigned identifier for the original job.
	 * @param newPriority The new priority to assign to this job. Note: Future resubmissions will use this priority value.
	 */
	long resubmitJob(long jobId, int newPriority);

	/**
	 * Attempts to cancel a batch job that is currently executing. If the job does not exist or otherwise cannot be
	 * cancelled, this method will return {@literal false}.
	 * @param jobId The MPF-assigned identifier for the batch job. The job must be a batch job.
	 * @return {@literal true} iff the batch job exists and was cancelled successfully.
	 */
	boolean cancel(long jobId);

	/**
	 * Attempts to cancel a streaming job that is currently executing. If the streaming job does not
	 * exist or otherwise cannot be cancelled, this method will return {@literal false}.
	 * @param jobId The MPF-assigned identifier for the streaming job. The job must be a streaming job.
	 * @param doCleanup if true, delete the streaming job files from disk after canceling the streaming job
	 * @return {@literal true} iff the streaming job exists and was cancelled successfully.
	 */
	boolean cancelStreamingJob(long jobId, Boolean doCleanup);

	/** Gets the marked-up media with the specified (batch job) id. */
	public MarkupResult getMarkupResult(long id);

	/** Gets the marked-up media associated with the specified (batch job) job. */
	public List<MarkupResult> getMarkupResultsForJob(long jobId);

	/** Gets the marked-up media associated with all (batch) jobs. */
	public List<MarkupResult> getAllMarkupResults();

	/**
	 * Gets the JobRequest instance in the persistent data store associated with the specified batch (job) id.
	 */
	public JobRequest getJobRequest(long id);

	/**
	 * Gets the StreamingJobRequest instance in the persistent data store associated with the
	 * specified streaming (job) id.
	 */
	public StreamingJobRequest getStreamingJobRequest(long id);

	/**
	 * Gets all of the JobRequest (batch job) instances in the persistent data store.
	 */
	public List<JobRequest> getAllJobRequests();

	/**
	 * Gets all of the StreamingJobRequest (streaming job) instances in the persistent data store.
	 */
	public List<StreamingJobRequest> getAllStreamingJobRequests();

	public List<SystemMessage> getSystemMessagesByType(String filterbyType);

	public List<SystemMessage> getSystemMessagesByRemoveStrategy(String filterbyRemoveStrategy );

	/** adds a System Message or updates the one that is already in the DB
	 *  returns the System Message
	 */
	public SystemMessage addSystemMessage( SystemMessage obj );

	/** adds one of the standard System Messages ID'ed with msgID;
	 *  returns the System Message
	 */
	public SystemMessage addStandardSystemMessage( String msgID );

	/** deletes the System Message with msgId
	 *  returns the System Message that was originally in the DB, or null if it was not in the DB
	 */
	public SystemMessage deleteSystemMessage(long msgId );

	/** deletes the System Message with msgEnum
	 *  returns the System Message that was originally in the DB, or null if it was not in the DB
	 */
	public SystemMessage deleteStandardSystemMessage(String msgEnum );
}

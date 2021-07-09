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

package org.mitre.mpf.wfm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.*;
import org.junit.runners.MethodSorters;
import org.mitre.mpf.interop.JsonCallbackBody;
import org.mitre.mpf.interop.util.TimeUtils;
import org.mitre.mpf.rest.api.*;
import org.mitre.mpf.test.TestUtil;
import org.mitre.mpf.wfm.ui.Utils;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mitre.mpf.wfm.util.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Spark;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;


//mvn -Dtest=ITWebREST test if running tomcat before
//mvn verify -Dtest=none -DfailIfNoTests=false -Dit.test=ITWebREST

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ITWebREST {

	private static final int MINUTES = 1000 * 60; // 1000 milliseconds/sec, 60 sec/minute
	private static final String TEST_PIPELINE_NAME = "OCV FACE DETECTION PIPELINE";

	// based on the registered components, this may not be a complete list of pipelines
	private static final String[] TEST_PIPELINES = {
			"MOG MOTION DETECTION (WITH TRACKING) PIPELINE",
			"OALPR LICENSE PLATE TEXT DETECTION PIPELINE",
			"OALPR LICENSE PLATE TEXT DETECTION (WITH MARKUP) PIPELINE",
			"OALPR LICENSE PLATE TEXT DETECTION (WITH MOG MOTION PREPROCESSOR) PIPELINE",
			"OALPR LICENSE PLATE TEXT DETECTION (WITH MOG MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"OALPR LICENSE PLATE TEXT DETECTION (WITH SUBSENSE MOTION PREPROCESSOR) PIPELINE",
			"OALPR LICENSE PLATE TEXT DETECTION (WITH SUBSENSE MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"OCV FACE DETECTION PIPELINE",
			"OCV FACE DETECTION (WITH MARKUP) PIPELINE",
			"OCV FACE DETECTION (WITH MOG MOTION PREPROCESSOR) PIPELINE",
			"OCV FACE DETECTION (WITH MOG MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"OCV FACE DETECTION (WITH SUBSENSE MOTION PREPROCESSOR) PIPELINE",
			"OCV FACE DETECTION (WITH SUBSENSE MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"DLIB FACE DETECTION PIPELINE",
			"DLIB FACE DETECTION (WITH MARKUP) PIPELINE",
			"DLIB FACE DETECTION (WITH MOG MOTION PREPROCESSOR) PIPELINE",
			"DLIB FACE DETECTION (WITH MOG MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"DLIB FACE DETECTION (WITH SUBSENSE MOTION PREPROCESSOR) PIPELINE",
			"DLIB FACE DETECTION (WITH SUBSENSE MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"OCV PERSON DETECTION PIPELINE",
			"OCV PERSON DETECTION (WITH MARKUP) PIPELINE",
			"OCV PERSON DETECTION (WITH MOG MOTION PREPROCESSOR) PIPELINE",
			"OCV PERSON DETECTION (WITH MOG MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"OCV PERSON DETECTION (WITH SUBSENSE MOTION PREPROCESSOR) PIPELINE",
			"OCV PERSON DETECTION (WITH SUBSENSE MOTION PREPROCESSOR AND MARKUP) PIPELINE",
			"SPHINX SPEECH DETECTION PIPELINE",
			"SPHINX SPEECH DETECTION (WITH MARKUP) PIPELINE"
	};

	// based on the registered components, this may not be a complete list of services
	private static final String[] TEST_SERVICES = { "Markup" };

	private static final Logger log = LoggerFactory.getLogger(ITWebREST.class);

	private static final ObjectMapper objectMapper = ObjectMapperFactory.customObjectMapper();

	private static final IoUtils ioUtils = new IoUtils();

	private static long job_created_id = -1L;
	private static boolean test_ready = true;
	private static String JSONstring;
	private static int testCtr = 0;
	private static long processedJobId = -1;

	private long starttime = 0;

	// run once
	@BeforeClass
	public static void initialize() throws InterruptedException, IOException {
		log.info("Starting the REST Tests");
		job_created_id = createNewJob();
		if (!WebRESTUtils.waitForJobToTerminate(job_created_id, 5000)) {
			test_ready = false;
			throw new InterruptedException("job did not end");
		}
	}

	// run once
	@AfterClass
	public static void shutdown() {
		log.info("Finished REST Tests");
	}

	private void startTest(String testname, String url) throws MalformedURLException {
		// an assumption failure causes the test to be ignored
		Assume.assumeTrue("Skipping test. Not ready. A prior test or condition failed.", test_ready);
		test_ready = false;
		testCtr++;
		log.info("Beginning test #{}: {}. REST: [{}]", testCtr, testname, url);
		starttime = DateTime.now().getMillis();
	}

	private void endTest() {
		long end = DateTime.now().getMillis() - starttime;
		log.info("Finished test #{}. Time elapsed: {} milliseconds ", testCtr,end);
		test_ready = true;
	}

	// ///////////////////////////
	// Tests
	// ///////////////////////////

	//rest/media/process
	//will use the job id for cancel and resubmit
	@Test(timeout = 1 * MINUTES)
	public void test1ProcessMedia() throws Exception {
		String url = WebRESTUtils.REST_URL + "jobs";
		startTest("test1ProcessMedia",url);

		//a video will be good to test being able to cancel before completion!
		String mediaPathUrl = ioUtils.findFile("/samples/new_face_video.avi").toString();

		JobCreationRequest jobCreationRequest = new JobCreationRequest();
		jobCreationRequest.getMedia().add(new JobCreationMediaData(mediaPathUrl));
		jobCreationRequest.setPipelineName("OCV FACE DETECTION PIPELINE");
		//jobCreationRequest.setPriority(priority);

		//convert params to json string
		String params = objectMapper.writeValueAsString(jobCreationRequest);

		URL actualUrl = new URL(url);
		String response = WebRESTUtils.postJSON(actualUrl, params, WebRESTUtils.MPF_AUTHORIZATION);

		JobCreationResponse jobCreationResponse = objectMapper.readValue(response, JobCreationResponse.class);

		//null error message and JobId >= 1, could check error code as well
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, jobCreationResponse.getMpfResponse().getResponseCode());
		Assert.assertNull(jobCreationResponse.getMpfResponse().getMessage());
		Assert.assertTrue(jobCreationResponse.getJobId() >= 1);

		processedJobId = jobCreationResponse.getJobId();
		//use this id for resubmit and cancel testing
		log.info("processedJobId: " + processedJobId);

		endTest();
	}

	/**
	 * rest/jobs/{id}/cancel
	 */
	@Test(timeout = 5 * MINUTES) // it may take some time for the job to get to a terminal (CANCELLED) state
	// make sure this runs after test1ProcessMedia()
	public void test2CancelInProgressJob() throws Exception {
		String url = WebRESTUtils.REST_URL + "jobs/" + Long.toString(processedJobId) + "/cancel";
		startTest("test2CancelInProgressJob",url);

		SingleJobInfo singleJobInfo = null;
		//wait till ready to attempt a job cancellation
		do {
			singleJobInfo = WebRESTUtils.getSingleJobInfo(processedJobId);

			//check every 0.5 seconds
			Thread.sleep(500);
		} while (singleJobInfo == null || (!singleJobInfo.getJobStatus().equals("IN_PROGRESS") && !singleJobInfo.isTerminal()));

		Assert.assertEquals("IN_PROGRESS", singleJobInfo.getJobStatus());

		//jobId - REQUIRED
		//create params object
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		URL actualUrl = new URL(url);
		String response = /*WebRESTUtils.postJSON*/ WebRESTUtils.postParams(actualUrl, params, WebRESTUtils.MPF_AUTHORIZATION, 200);
		MpfResponse mpfResponse = objectMapper.readValue(response, MpfResponse.class);

		//looking for isSuccess to be true and null error message
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, mpfResponse.getResponseCode());
		Assert.assertNull(mpfResponse.getMessage());

		singleJobInfo = null;
		//wait till job is in a terminal state to verify the job has been CANCELLED
		do {
			singleJobInfo = WebRESTUtils.getSingleJobInfo(processedJobId);

			//check every 0.5 seconds
			Thread.sleep(500);
		} while (singleJobInfo == null || !singleJobInfo.isTerminal());

		Assert.assertEquals("CANCELLED", singleJobInfo.getJobStatus());

		endTest();
	}

	/**
	 * rest/jobs/{id}/resubmit
	 */
	@Test(timeout = 2 * MINUTES)
	// make sure this runs after test2CancelInProgressJob()
	public void test3ResubmitCancelledJob() throws Exception {
		String url = WebRESTUtils.REST_URL + "jobs/" + Long.toString(processedJobId) + "/resubmit";
		startTest("test3ResubmitCancelledJob",url);

		//need to make sure the job is in a terminal state before trying to resubmit!
		String urlJobsStatus = WebRESTUtils.REST_URL + "jobs/" + processedJobId + ".json";

		SingleJobInfo singleJobInfo = null;
		//wait till ready to attempt a job resubmission
		do {
			singleJobInfo = WebRESTUtils.getSingleJobInfo(processedJobId);

			//check every three seconds
			Thread.sleep(3000);
		} while( !(singleJobInfo!= null && singleJobInfo.isTerminal() && singleJobInfo.getJobStatus().equals("CANCELLED")) );

		//jobId - REQUIRED - now a path variable
		//jobPriority - OPTIONAL
		//create params object
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("jobPriority", "9"));
		URL actualUrl = new URL(url);
		String response = WebRESTUtils.postParams(actualUrl, params, WebRESTUtils.MPF_AUTHORIZATION, 200);

		JobCreationResponse jobCreationResponse = objectMapper.readValue(response, JobCreationResponse.class);

		//null error message and verifying the resubmitted job id is equal to the processedJobId
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, jobCreationResponse.getMpfResponse().getResponseCode());
		Assert.assertNull(jobCreationResponse.getMpfResponse().getMessage());
		Assert.assertEquals(jobCreationResponse.getJobId(), processedJobId);

		singleJobInfo = null;
		//wait till job is complete to prevent logs from this job showing up in other tests...
		do {
			singleJobInfo = WebRESTUtils.getSingleJobInfo(processedJobId);

			//check every three seconds
			Thread.sleep(3000);
		} while( !(singleJobInfo!= null && singleJobInfo.isTerminal() && singleJobInfo.getJobStatus().equals("COMPLETE")) );

		Assert.assertTrue(singleJobInfo.getJobStatus().equals("COMPLETE"));

		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void testRestNoAuth() throws Exception {
		String urlStr = WebRESTUtils.REST_URL + "jobs/stats.json";
		startTest("testRestNoAuth", urlStr);

		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		Assert.assertEquals(401, conn.getResponseCode());

		endTest();
	}

	/*
	 * rest/jobs (ALL) is not currently exposed
	 */
//	@Test(timeout = 1 * MINUTES)
//	public void testPing_Jobs_Status() throws Exception {
//		String url = WebRESTUtils.REST_URL + "jobs.json";
//		startTest("testPing_Jobs_Status",url);
//		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
//		JSONArray array = new JSONArray(JSONstring);
//		log.info("array length :" + array.length());
//		Assert.assertTrue(array.length() >= 0);
//		endTest();
//	}

	/*
	 * rest/jobs (ALL) is not currently exposed
	 */
//	@Test(timeout = 1 * MINUTES)
//	public void test_Jobs_Status() throws Exception {
//		String url = WebRESTUtils.REST_URL + "jobs.json";
//		startTest("test_Jobs_Status",url);
//		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
//		JSONArray array = new JSONArray(JSONstring);
//		log.info("array length :" + array.length());
//		if (array.length() == 0) {
//			log.info("[Jobs_Status] No jobs to check the status.");
//			Assert.assertTrue(false);
//		} else {
//			boolean found = false;
//			//find our job
//			for (int i = 0;i<array.length();i++) {
//				JSONObject job = array.getJSONObject(i);
//				log.debug("[Jobs_Status] job :" + job);
//				if(job.getLong("jobId") == Long.parseLong(job_created_id )){
//					log.info("[Jobs_Status] job found :" + job);
//						Assert.assertTrue(job.getLong("startDate") > 0);
//						Assert.assertTrue(job.getInt("jobId") > 0);
//						Assert.assertTrue(job.getLong("endDate") > 0);
//						Assert.assertTrue(job.getString("pipelineName").length() > 0);
//						Assert.assertTrue(job.getString("pipelineName").equals(TEST_PIPELINE_NAME));
//						Assert.assertTrue(job.getString("jobStatus").equals("COMPLETE"));
//						Assert.assertTrue(job.getString("outputObjectPath").length() > 0);
//						Assert.assertTrue(job.getString("outputObjectPath").endsWith("detection.json"));
//
//					found =true;
//					break;
//				}
//			}
//			Assert.assertTrue(found);
//		}
//		endTest();
//	}

	/*
	 * rest/jobs (ALL) is not currently exposed, using the exposed
	 * rest/jobs/{id} endpoint instead
	 */
	@Test(timeout = 1 * MINUTES)
	public void test_Jobs_Status_Single() throws Exception {
		String url = WebRESTUtils.REST_URL + "jobs/" + job_created_id + ".json";
		startTest("test_Jobs_Status",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);

		boolean found = false;
		//find our job
		JSONObject job = new JSONObject(JSONstring); // array.getJSONObject(i);
		log.debug("[Jobs_Status] job :" + job);
		if(job.getLong("jobId") == job_created_id){
			log.info("[Jobs_Status] job found :" + job);
			Assert.assertTrue(TimeUtils.toInstant(job.getString("startDate")).toEpochMilli() > 0);
			Assert.assertTrue(job.getInt("jobId") > 0);
			Assert.assertTrue(TimeUtils.toInstant(job.getString("endDate")).toEpochMilli() > 0);
			Assert.assertTrue(job.getString("pipelineName").length() > 0);
			Assert.assertTrue(job.getString("pipelineName").equals(TEST_PIPELINE_NAME));
			Assert.assertTrue(job.getString("jobStatus").equals("COMPLETE"));
			Assert.assertTrue(job.getString("outputObjectPath").length() > 0);
			Assert.assertTrue(job.getString("outputObjectPath").endsWith("detection.json"));

			found =true;
		}

		Assert.assertTrue(found);

		endTest();
	}

	/***
	 * @throws Exception
	 */
	@Test(timeout = 1 * MINUTES)
	public void testPing_Jobs_SerializedOutput() throws Exception {
		// just see if endpoint is there
		String url = WebRESTUtils.REST_URL + "jobs/" +  job_created_id + "/output/detection" ;
		startTest("testPing_Jobs_SerializedOutput",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		Assert.assertTrue(JSONstring != null);
		endTest();
	}

	@Test(timeout = 2 * MINUTES)
	public void test_Jobs_SerializedOutput() throws Exception {
		String postJobsUrl = WebRESTUtils.REST_URL + "jobs";
		startTest("test_Jobs_SerializedOutput - postJobsUrl",postJobsUrl);
		String detPipeline = "OCV FACE DETECTION PIPELINE";

		//a video will be good to test being able to cancel before completion!
		String resourcePath = "/samples/new_face_video.avi";
		String mediaPathUrl = ioUtils.findFile(resourcePath).toString();

		JobCreationRequest jobCreationRequest = new JobCreationRequest();
		jobCreationRequest.getMedia().add(new JobCreationMediaData(mediaPathUrl));
		jobCreationRequest.setPipelineName(detPipeline);
		jobCreationRequest.setPriority(7); //why not

		//convert params to json string
		String params = objectMapper.writeValueAsString(jobCreationRequest);

		URL actualUrl = new URL(postJobsUrl);
		String response = WebRESTUtils.postJSON(actualUrl, params, WebRESTUtils.MPF_AUTHORIZATION);

		JobCreationResponse jobCreationResponse = objectMapper.readValue(response, JobCreationResponse.class);

		//check message, responseCode, and jobId
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, jobCreationResponse.getMpfResponse().getResponseCode());
		Assert.assertNull(jobCreationResponse.getMpfResponse().getMessage());
		Assert.assertTrue(jobCreationResponse.getJobId() >= 1);

		long completeJobId = jobCreationResponse.getJobId();
		//use this id for resubmit and cancel testing
		log.info("completeJobId: " + completeJobId);

		//Need to wait for the job to complete
		log.info("Waiting for job with id '{}' to complete", completeJobId);
		String urlJobsStatus = WebRESTUtils.REST_URL + "jobs/" + completeJobId;

		SingleJobInfo singleJobInfo = null;
		//wait till ready to attempt a job cancellation
		do {
			String jsonSingleJobInfoStr = WebRESTUtils.getJSON(new URL(urlJobsStatus), WebRESTUtils.MPF_AUTHORIZATION);
			singleJobInfo  = objectMapper.readValue(jsonSingleJobInfoStr, SingleJobInfo.class);

			//check every three seconds
			Thread.sleep(3000);
		} while( !(singleJobInfo != null && singleJobInfo.isTerminal() && singleJobInfo.getJobStatus().startsWith("COMPLETE")) );

		endTest();

		log.info("Job now complete using pipeline '{}'", detPipeline);
		String baseOutputUrl = WebRESTUtils.REST_URL + "jobs/" +  completeJobId + "/output/" ;

		String outputObjectType = "detection";
		String url = baseOutputUrl + outputObjectType;
		startTest("test_Jobs_SerializedOutput - " + outputObjectType,url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		Assert.assertTrue(JSONstring != null);// returns a path to the file
		// created during job creation
		log.info("[test_Jobs_SerializedOutput] json length :" + JSONstring.length());
		JSONParser parser = new JSONParser();
		org.json.simple.JSONObject json = (org.json.simple.JSONObject) parser
				.parse(JSONstring);
		log.info("[test_Jobs_SerializedOutput] - {} - json: {}", outputObjectType,
				json.toString());
		if(outputObjectType.equals("detection")) {
			Assert.assertTrue((long) json.get("jobId") == completeJobId);
			Assert.assertTrue(((String) json.get("objectId")).length() > 0);
			Assert.assertTrue(((String) json.get("timeStart")).length() > 0);
			org.json.simple.JSONObject pipeline = (org.json.simple.JSONObject) json.get("pipeline");
			Assert.assertTrue(((String) pipeline.get("name")).equals(detPipeline));
			Assert.assertTrue(Integer.parseInt(json.get("priority").toString()) == 7);
			org.json.simple.JSONArray arr = (org.json.simple.JSONArray) json.get("media");
			org.json.simple.JSONObject media = (org.json.simple.JSONObject) arr.get(0);
			String path = (String) media.get("path");
			Assert.assertTrue(path.endsWith(resourcePath));
			Assert.assertTrue(((String) media.get("status")).equals("COMPLETE"));
		} else {
			//bad object type
			Assert.assertTrue(false);
		}
		endTest();
	}

	//TOOD: use the new model
	@Test(timeout = 1 * MINUTES)
	public void testPing_Jobs_Stats() throws Exception {
		String url = WebRESTUtils.REST_URL + "jobs/stats.json";
		startTest("testPing_Jobs_Stats",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONObject obj = new JSONObject(JSONstring);
		Assert.assertTrue(obj.has("totalJobs") && obj.has("aggregatePipelineStatsMap")
				&& obj.has("elapsedTimeMs") && obj.has("jobTypes"));
		//TODO: use model and do a comparison
		endTest();
	}

	//TOOD: use the new model
	@Test(timeout = 1 * MINUTES)
	public void test_Jobs_Stats() throws Exception {
		String url = WebRESTUtils.REST_URL + "jobs/stats.json";
		startTest("test_Jobs_Stats",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		log.info("[test_Jobs_Stats] json:" + JSONstring);
		JSONObject objs = new JSONObject(JSONstring);
		boolean found = false;
		Assert.assertTrue(objs.has("totalJobs") && objs.has("aggregatePipelineStatsMap")
				&& objs.has("elapsedTimeMs") && objs.has("jobTypes"));
		if (objs.getInt("totalJobs") > 0) {
			JSONObject data = objs.getJSONObject("aggregatePipelineStatsMap");
			Iterator<String> keys = data.keys();
			while (keys.hasNext()) {
				String key = keys.next();
				if (key.equals(TEST_PIPELINE_NAME))
					found = true;// see atleast if the job that was created is in there
			}
		}

		//TODO: use model and do a comparison

		Assert.assertTrue(found);
		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void testPing_Pipelines_Available() throws Exception {
		String url = WebRESTUtils.REST_URL + "pipelines.json";
		startTest("testPing_Pipelines_Available",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONArray array = new JSONArray(JSONstring);
		log.info("array length :" + array.length());
		Assert.assertTrue(array.length() >= 0);
		endTest();
	}

	/***
	 * Make sure the list of pipelines match the test list of pipeline
	 *
	 * @throws Exception
	 */
	@Test(timeout = 1 * MINUTES)
	public void test_Pipelines_Available() throws Exception {
		String url = WebRESTUtils.REST_URL + "pipelines.json";
		startTest("test_Pipelines_Available",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONArray pipelines = new JSONArray(JSONstring);
		Assert.assertTrue(pipelines.length() >= 0);
		log.info("Pipelines available: (" + pipelines.length() + ") ");
		log.debug("Pipelines available: " + JSONstring);
		log.info("Pipelines testing : (" + TEST_PIPELINES.length + ")");
		JSONArray testing_pipelines = new JSONArray(TEST_PIPELINES);
		Assert.assertTrue(testing_pipelines.length() >= 0);

		for (int i = 0; i < testing_pipelines.length(); i++) {
			String test_pipeline = testing_pipelines.getString(i);
			boolean found = false;
			for (int j = 0; j < pipelines.length(); j++) {
				JSONObject pipeline = pipelines.getJSONObject(j);
				if (pipeline.getString("name").equals(test_pipeline)) {
					found = true;
					break;
				}
			}
			if (!found) {
				log.error("Pipeline not found: " + test_pipeline);
			}
			Assert.assertTrue(found);
		}

		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void testPing_NodeManager_getNodeManagerInfo() throws Exception {
		TestUtil.assumeNodeManagerEnabled();

		String url = WebRESTUtils.REST_URL + "nodes/info.json";
		startTest("testPing_NodeManager_getNodeManagerInfo",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONObject obj = new JSONObject(JSONstring);
		JSONArray array = obj.getJSONArray("nodeModels");
		log.info("array length :" + array.length());
		Assert.assertTrue(array.length() >= 0);// 16
		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void test_NodeManager_getNodeManagerInfo() throws Exception {
	    TestUtil.assumeNodeManagerEnabled();

		String url = WebRESTUtils.REST_URL + "nodes/info.json";
		startTest("test_NodeManager_getNodeManagerInfo",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONObject obj = new JSONObject(JSONstring);
		JSONArray array = obj.getJSONArray("nodeModels");

		for (String test_service : TEST_SERVICES) {
			log.debug("service:" + test_service);
			JSONObject service = null;
			for (int i = 0; i < array.length(); i++) {
				if (array.getJSONObject(i).getString("name").toLowerCase().contains(test_service.toLowerCase())) {
					service = array.getJSONObject(i);
					break;
				}
			}
			Assert.assertTrue(service != null);
			Assert.assertTrue(service.getInt("rank") >= 0);
			Assert.assertTrue(service.getInt("serviceCount") >= 0);
			Assert.assertTrue(service.getInt("restartCount") >= 0);
			Assert.assertTrue(service.getString("kind").equals("simple")
					|| service.getString("kind").equals("generic"));
		}
		endTest();
	}

	// nodes/config GET
	@Test(timeout = 1 * MINUTES)
	public void testPing_NodeManager_getNodeManagerConfig() throws Exception {
	    TestUtil.assumeNodeManagerEnabled();

		String url = WebRESTUtils.REST_URL + "nodes/config.json";
		startTest("testPing_NodeManager_getNodeManagerConfig",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONArray array = new JSONArray(JSONstring);
		log.info("array length :" + array.length());

		Assert.assertTrue(array.length() > 0);
		JSONObject obj = array.getJSONObject(0);
		JSONArray array2 = obj.getJSONArray("services");
		Assert.assertTrue(array2.length() >= 0);
		endTest();
	}

	/**
	 * Test the first node for a few services and make sure
	 * they have some correct fields
	 **/
	@Test(timeout = 1 * MINUTES)
	public void test_NodeManager_getNodeManagerConfig() throws Exception {
	    TestUtil.assumeNodeManagerEnabled();

		String url = WebRESTUtils.REST_URL + "nodes/config.json";
		startTest("test_NodeManager_getNodeManagerConfig",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		log.info("[test_NodeManager_getNodeManagerConfig] GET:"+url);
		JSONArray array = new JSONArray(JSONstring);
		log.info("[test_NodeManager_getNodeManagerConfig] services :" + JSONstring);

		Assert.assertTrue(array.length() > 0);
		JSONObject obj = array.getJSONObject(0);
		Assert.assertTrue(obj.getString("host").length() > 0);
		JSONArray services = obj.getJSONArray("services");
		Assert.assertTrue(services.length() >= 0);
		log.info("[test_NodeManager_getNodeManagerConfig] services :" + services.length());

		for (String test_service : TEST_SERVICES) {
			log.info("[test_NodeManager_getNodeManagerConfig] Verifying Service Exists:" + test_service);
			JSONObject service = null;
			for (int i = 0; i < services.length(); i++) {
				if (services.getJSONObject(i).getString("serviceName").toLowerCase().equals(test_service.toLowerCase())) {
					log.info("[test_NodeManager_getNodeManagerConfig] Service Found:" + test_service);
					service = services.getJSONObject(i);
					break;
				}
			}
			log.info("{} found {}", test_service, service != null);
			Assert.assertTrue(service != null);
			Assert.assertTrue(service.getString("cmd").length() >= 0);
		}

		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void test_NodeManager_saveNodeManagerConfigPOST() throws Exception {
	    TestUtil.assumeNodeManagerEnabled();

		String url = WebRESTUtils.REST_URL + "nodes/config";
		//get the current config
		String config = WebRESTUtils.REST_URL + "nodes/config.json";
		startTest("test_NodeManager_saveNodeManagerConfigPOST",config);
		JSONstring = WebRESTUtils.getJSON(new URL(config), WebRESTUtils.MPF_AUTHORIZATION);
		log.info("[saveNodeManagerConfigPOST] original config:"+JSONstring);
		String orig_config =JSONstring;
		JSONArray array = new JSONArray(JSONstring);
		Assert.assertTrue(array.length() > 0);
		//modify original by removing first service
		JSONObject obj = array.getJSONObject(0);
		JSONArray services = obj.getJSONArray("services");
		JSONObject first = services.getJSONObject(0);
		log.info("[saveNodeManagerConfigPOST] Removing Service:" + first.getString("serviceName"));
		services.remove(0);
		array.remove(0);
		obj.put("services", services);
		array.put(obj);

		//post back
		String params = array.toString();
		log.debug("[saveNodeManagerConfigPOST]  post {} params:"+params,url);
		//requires admin auth
		JSONstring = WebRESTUtils.postJSON(new URL(url), params, WebRESTUtils.ADMIN_AUTHORIZATION);
		log.debug("[saveNodeManagerConfigPOST]  post results:"+JSONstring);//should return true

		Assert.assertNotNull(JSONstring);
		MpfResponse mpfResponse = objectMapper.readValue(JSONstring, MpfResponse.class);

		//verify that the config did save
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, mpfResponse.getResponseCode());
		Assert.assertNull(mpfResponse.getMessage());
		log.info("[saveNodeManagerConfigPOST]  Waiting 10 seconds for changes to complete");
		Thread.sleep(10000);

		//verify changes by pulling the config and make sure the service is missing
		log.debug("[saveNodeManagerConfigGet]  {}",url);
		JSONstring = WebRESTUtils.getJSON(new URL(config), WebRESTUtils.MPF_AUTHORIZATION);
		log.info("[saveNodeManagerConfigPOST]  new config:"+JSONstring);
		array = new JSONArray(JSONstring);
		Assert.assertTrue(array.length() >= 0);
		obj = array.getJSONObject(0);
		services = obj.getJSONArray("services");
		for (int i = 0; i < services.length(); i++) {
			JSONObject service = services.getJSONObject(i);
			log.debug("Service:" + service.toString());
			Assert.assertFalse(service.getString("serviceName").equals(first.getString("serviceName")));
		}

		//cleanup - add back in for future tests if needed
		log.info("Restoring original configuration - post {}",url);
		//requires admin auth
		JSONstring = WebRESTUtils.postJSON(new URL(url), orig_config, WebRESTUtils.ADMIN_AUTHORIZATION);

		log.debug("post results:"+JSONstring);

		mpfResponse = objectMapper.readValue(JSONstring, MpfResponse.class);
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, mpfResponse.getResponseCode());
		Assert.assertNull(mpfResponse.getMessage());

		log.info("Waiting 10 seconds for changes to complete");
		Thread.sleep(10000);
		//Maybe verify it set?

		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void test_NodeManager_shutdown_startService() throws Exception {
	    TestUtil.assumeNodeManagerEnabled();

		startTest("test_NodeManager_shutdown_startService","");
		JSONArray nodes = WebRESTUtils.getNodes();
		Assert.assertTrue(nodes.length() > 0);
		// get the first node that is running
		JSONObject node = null;
		for (int i = 0; i < nodes.length(); i++) {
			node = nodes.getJSONObject(i);
			log.info("Node:" + node.toString());
			if (node.getString("lastKnownState").equals("Running"))
				break;
		}
		if (node == null) {
			log.error("No node is 'Running'");
			Assert.assertTrue(false);
		}
		String service_name = node.getString("name");
		Assert.assertTrue(service_name != null && service_name.length() > 0);

		/*
		 * stop service tests
		 */
		List<NameValuePair> paramsList = new ArrayList<NameValuePair>();

		//make sure this fails with regular mpf auth (401)
		String url = WebRESTUtils.REST_URL + "nodes/services/" + service_name + "/stop" ;
		log.info("test_NodeManager_shutdownService get {}",url);
		//this will throw an IOException
		JSONstring = WebRESTUtils.postParams(new URL(url), paramsList, WebRESTUtils.MPF_AUTHORIZATION, 401);
		//make sure response is null
		Assert.assertNull(JSONstring);

		//try with bad service name cat:asdfjkl:4
		url = WebRESTUtils.REST_URL + "nodes/services/" + "cat:asdfjkl:4" + "/stop" ;
		log.info("test_NodeManager_shutdownService get {}",url);
		JSONstring = WebRESTUtils.postParams(new URL(url), paramsList, WebRESTUtils.ADMIN_AUTHORIZATION, 200);
		//convert JSONString to mpfResponse
		MpfResponse mpfResponse = objectMapper.readValue(JSONstring, MpfResponse.class);
		//not a success
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_ERROR, mpfResponse.getResponseCode());
		//make sure service is in the response message
		Assert.assertTrue(mpfResponse.getMessage().contains("service"));

		//requires admin auth
		url = WebRESTUtils.REST_URL + "nodes/services/" + service_name + "/stop" ;
		log.info("test_NodeManager_shutdownService get {}",url);
		JSONstring = WebRESTUtils.postParams(new URL(url), paramsList, WebRESTUtils.ADMIN_AUTHORIZATION, 200);
		//convert JSONString to mpfResponse
		mpfResponse = objectMapper.readValue(JSONstring, MpfResponse.class);
		//success
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, mpfResponse.getResponseCode());
		//make sure service is null on success
		Assert.assertTrue(mpfResponse.getMessage() == null);
		Thread.sleep(3000);// give it some time to shut down

		// verify service is shut down
		nodes = WebRESTUtils.getNodes();
		Assert.assertTrue(nodes.length() > 0);
		boolean completed = false;
		for (int i = 0; i < nodes.length(); i++) {
			JSONObject verify_node = nodes.getJSONObject(i);
			log.info("Node:" + verify_node.toString());
			if (node.getString("name").equals(verify_node.getString("name"))
					&& !verify_node.getString("lastKnownState").equals(
					"Running")) {
				completed = true;
				break;
			}
		}
		Assert.assertTrue(completed);

		/*
		 * start service tests
		 */
		//make sure this fails with regular mpf auth (401)
		url = WebRESTUtils.REST_URL + "nodes/services/" + service_name + "/start" ;
		log.info("test_NodeManager_shutdownService get {}",url);
		//this will throw an IOException
		JSONstring = WebRESTUtils.postParams(new URL(url), paramsList, WebRESTUtils.MPF_AUTHORIZATION, 401);
		//make sure response is null
		Assert.assertNull(JSONstring);

		//try with bad service name cat:asdfjkl:7
		url = WebRESTUtils.REST_URL + "nodes/services/" + "cat:asdfjkl:7" + "/start" ;
		log.info("test_NodeManager_shutdownService get {}",url);
		JSONstring = WebRESTUtils.postParams(new URL(url), paramsList, WebRESTUtils.ADMIN_AUTHORIZATION, 200);
		//convert JSONString to mpfResponse
		mpfResponse = objectMapper.readValue(JSONstring, MpfResponse.class);
		//not a success
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_ERROR, mpfResponse.getResponseCode());
		//make sure service is in the response message
		Assert.assertTrue(mpfResponse.getMessage().contains("service"));

		url = WebRESTUtils.REST_URL + "nodes/services/" + service_name + "/start" ;
		log.info("test_NodeManager_shutdown_startService get {}",url);
		paramsList = new ArrayList<NameValuePair>();
		//requires admin auth
		JSONstring = WebRESTUtils.postParams(new URL(url), paramsList, WebRESTUtils.ADMIN_AUTHORIZATION, 200);
		//convert JSONString to mpfResponse
		mpfResponse = objectMapper.readValue(JSONstring, MpfResponse.class);
		//success
		Assert.assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, mpfResponse.getResponseCode());
		//make sure service is null on success
		Assert.assertTrue(mpfResponse.getMessage() == null);
		Thread.sleep(3000);// give it some time to shut down

		// verify service is shut down
		nodes = WebRESTUtils.getNodes();
		Assert.assertTrue(nodes.length() > 0);
		completed = false;
		for (int i = 0; i < nodes.length(); i++) {
			JSONObject verify_node = nodes.getJSONObject(i);
			log.info("Node:" + verify_node.toString());
			if (node.getString("name").equals(verify_node.getString("name"))
					&& verify_node.getString("lastKnownState").equals("Running")) {
				completed = true;
				break;
			}
		}
		Assert.assertTrue(completed);
		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void test_MediaProcess() throws Exception {
		startTest("test_MediaProcess","");
		Assert.assertTrue(job_created_id >= 0);
		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void testPing_Pipelines() throws Exception {
		String url = WebRESTUtils.REST_URL + "pipelines.json";
		startTest("testPing_Pipelines",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONArray array = new JSONArray(JSONstring);
		log.info("array length :" + array.length());
		Assert.assertTrue(array.length() >= 0);
		endTest();
	}

	@Test(timeout = 1 * MINUTES)
	public void testPing_NodeManagerInfo() throws Exception {
		TestUtil.assumeNodeManagerEnabled();

		String url = WebRESTUtils.REST_URL + "nodes/info.json";
		startTest("testPing_NodeManagerInfo",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONObject obj = new JSONObject(JSONstring);
		JSONArray array =obj.getJSONArray("nodeModels");
		log.info("array length :" + array.length());
		Assert.assertTrue(array.length() >= 0);
		endTest();
	}

	//nodes/config GET
	@Test(timeout = 1 * MINUTES)
	public void testPing_NodeManagerConfig() throws Exception {
		TestUtil.assumeNodeManagerEnabled();

		String url = WebRESTUtils.REST_URL + "nodes/config.json";
		startTest("testPing_NodeManagerConfig",url);
		JSONstring = WebRESTUtils.getJSON(new URL(url), WebRESTUtils.MPF_AUTHORIZATION);
		JSONArray array = new JSONArray(JSONstring);
		log.info("array length :" + array.length());

		Assert.assertTrue(array.length() > 0);
		JSONObject obj = array.getJSONObject(0);
		JSONArray array2 =obj.getJSONArray("services");
		Assert.assertTrue(array2.length() >= 0);
		endTest();
	}


/*
	//mvn -Dtest=ITWebREST#createLotsOfJobs test
	@Test
	public void createLotsOfJobs() throws Exception {
		int num = 2500;
		log.info("create "+num+" jobs");
		String url = WebRESTUtils.REST_URL + "jobs";
		JSONObject params = new JSONObject();
		params.put("pipelineName", "OCV FACE DETECTION PIPELINE");
		JSONArray params_media_urls = new JSONArray();
		params_media_urls.put(Utils.IMG_URL);
		params.put("mediaUris", params_media_urls);
		params.put("externalId", "external id");
		params.put("buildOutput", true);
		params.put("priority", 9);
		String param_string = params.toString();
		log.info("Post to: " + url + " Params: " + param_string);
		for(int i =0; i< num;i++){
			JSONstring = WebRESTUtils.postJSON(new URL(url), param_string, WebRESTUtils.MPF_AUTHORIZATION);
		}
		log.info("create lots of jobs complete");
	}
*/
	// /////////////////////////
	// Helpers
	// ///////////////////////////

	public static long createNewJob() throws MalformedURLException, InterruptedException {
		log.info("Creating new Job");
		String url = WebRESTUtils.REST_URL + "jobs";
		// create a JobCreationRequest
		JSONObject params = new JSONObject();
		params.put("pipelineName", TEST_PIPELINE_NAME);

		JSONArray mediaList = new JSONArray();
		JSONObject mediaEntry = new JSONObject();
		mediaList.put(mediaEntry);
		mediaEntry.put("mediaUri", Utils.IMG_URL);
		mediaEntry.put("properties", new JSONObject());
        params.put("media", mediaList);

		params.put("externalId", "external id");
		params.put("buildOutput", true);
		params.put("priority", 9);
		String param_string = params.toString();
		log.info("Post to: " + url + " Params: " + param_string);
		JSONstring = WebRESTUtils.postJSON(new URL(url), param_string, WebRESTUtils.MPF_AUTHORIZATION);
		log.info("results:" + JSONstring);
		JSONObject obj = new JSONObject(JSONstring);
		return Long.valueOf(obj.getInt("jobId"));
	}

	// /////////////////////////
	// Callback
	// ///////////////////////////

	private final CompletableFuture<JsonCallbackBody> getCallbackResult = new CompletableFuture<>();
	private final CompletableFuture<JsonCallbackBody> postCallbackResult = new CompletableFuture<>();

	@Test(timeout = 5 * MINUTES)
	public void testJobWithCallback() throws Exception {
		String externalId = "555";
		try {
			String url = WebRESTUtils.REST_URL + "jobs";
			startTest("testJobWithCallback",url);

			log.info("Creating new Job");

			// create a JobCreationRequest
			JSONObject params = new JSONObject();
			params.put("pipelineName", "OCV FACE DETECTION (WITH MARKUP) PIPELINE");

			JSONObject properties = new JSONObject();
			properties.put("testProp", "testVal");

			JSONArray media = new JSONArray();
			JSONObject medium = new JSONObject();
			medium.put("mediaUri", Utils.IMG_URL);
			medium.put("properties", properties);
			media.put(medium);

			params.put("media", media);
			params.put("externalId", externalId);
			params.put("buildOutput", true);
			params.put("priority", 9);
			params.put("callbackURL","http://0.0.0.0:20159/callback");
			params.put("callbackMethod","POST");
			String param_string = params.toString();

			setupSpark();//start the listener

			//submit a job request with a POST callback
			log.info("Post to: " + url + " Params: " + param_string);
			String postResponseJson = WebRESTUtils.postJSON(new URL(url), param_string, WebRESTUtils.MPF_AUTHORIZATION);
			log.info("results:" + postResponseJson);// {"errorCode":0,"errorMessage":null,"jobId":5}
			JSONObject obj = new JSONObject(postResponseJson);
			long jobId = obj.getLong("jobId");

			//wait for it to callback
			log.info("Waiting for POST callback...");
			JsonCallbackBody postCallbackContent = postCallbackResult.get();
			Assert.assertEquals(jobId, postCallbackContent.getJobId());
			Assert.assertEquals(externalId, postCallbackContent.getExternalId());

			Assert.assertTrue(postCallbackContent.getOutputObjectUri().startsWith("file:///"));
			Assert.assertTrue(postCallbackContent.getOutputObjectUri().endsWith(
					String.format("output-objects/%s/detection.json", jobId)));

			//test GET
			params.put("callbackMethod","GET");
			param_string = params.toString();
			log.info("Post to: " + url + " Params: " + param_string);
			postResponseJson = WebRESTUtils.postJSON(new URL(url), param_string, WebRESTUtils.MPF_AUTHORIZATION);
			log.info("results:" + postResponseJson);// {"errorCode":0,"errorMessage":null,"jobId":5}
			obj = new JSONObject(postResponseJson);
			jobId =  obj.getLong("jobId");

			//wait for it to callback
			log.info("Waiting for GET callback...");
			// The GET endpoint always reports an error to force retries.
			// The getCallbackResult future is only resolved after the required number callback
			// attempts have been made.
			// GET and POST both use the same code to handle callback failures.
			JsonCallbackBody getCallbackContent = getCallbackResult.get();
			Assert.assertEquals(jobId, getCallbackContent.getJobId());
			Assert.assertEquals(externalId, getCallbackContent.getExternalId());

			Assert.assertTrue(getCallbackContent.getOutputObjectUri().startsWith("file:///"));
			Assert.assertTrue(getCallbackContent.getOutputObjectUri().endsWith(
					String.format("output-objects/%s/detection.json", jobId)));

			var jobResponseObj = new JSONObject(WebRESTUtils.getJSON(new URL(url + '/' + jobId),
			                                                         WebRESTUtils.MPF_AUTHORIZATION));
			var jobStatus = jobResponseObj.getString("jobStatus");
			Assert.assertEquals("COMPLETE", jobStatus);

		} finally {
			endTest();
			Spark.stop();
		}
	}

	private void setupSpark() throws IOException {
		Spark.port(20159);
		Properties props = new Properties();
		try (var is = getClass().getResourceAsStream("/properties/mpf.properties")) {
		    props.load(is);
		}
		int numRetries = Integer.parseInt(props.getProperty("http.callback.retries", "10"));
		// +1 for the initial attempts
		int maxNumAttempts = numRetries + 1;

		AtomicInteger numAttempts = new AtomicInteger(0);

		Spark.get("/callback", (request, resp) -> {
			try {
			    long jobId = Long.parseLong(request.queryParams("jobid"));
				log.info("Spark received GET callback with url: " + request.url() + '?' + request.queryString());

				JsonCallbackBody callbackBody = new JsonCallbackBody(
						jobId, request.queryParams("externalid"), request.queryParams("outputobjecturi"));

				if (numAttempts.incrementAndGet() == maxNumAttempts) {
					getCallbackResult.complete(callbackBody);
				}
				resp.status(404);
				return "";
			}
			catch (Exception e) {
				getCallbackResult.completeExceptionally(e);
				throw e;
			}
		});

		Spark.post("/callback", (request, resp) -> {
			try {
				log.info("Spark received POST callback with body: " + request.body());
				JsonCallbackBody callbackBody = objectMapper.readValue(request.bodyAsBytes(),
				                                                       JsonCallbackBody.class);
				postCallbackResult.complete(callbackBody);
				return "";
			}
			catch (Exception e) {
				postCallbackResult.completeExceptionally(e);
				throw e;
			}
		});

		Spark.awaitInitialization();
	}
}

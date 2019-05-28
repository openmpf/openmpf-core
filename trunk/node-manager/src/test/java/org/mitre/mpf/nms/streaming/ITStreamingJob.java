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

package org.mitre.mpf.nms.streaming;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.mpf.nms.streaming.messages.LaunchStreamingJobMessage;
import org.mitre.mpf.nms.util.PropertiesUtil;
import org.mockito.stubbing.Answer;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class ITStreamingJob {

	@Rule
	public TemporaryFolder _tempDir = new TemporaryFolder();

	private PropertiesUtil _mockPropertiesUtil;

	private StreamingProcessFactory _mockProcessFactory;


	@Before
	public void init() {
		_mockPropertiesUtil = mock(PropertiesUtil.class);
		when(_mockPropertiesUtil.getIniFilesDir())
				.thenReturn(_tempDir.getRoot().toPath().resolve("mpf-ini-files"));

		_mockProcessFactory = mock(StreamingProcessFactory.class);

		//TODO: For future use.
//		when(_mockProcessFactory.createFrameReaderProcess(any()))
//				.then(createProcess("FrameReader"));
//
//		when(_mockProcessFactory.createVideoWriterProcess(any()))
//				.then(createProcess("VideoWriter"));

		when(_mockProcessFactory.createComponentProcess(any(), any(), any()))
				.then(createProcess("Component"));
	}


	@Test
	public void testStreamingJobWithPythonProcess() throws InterruptedException, TimeoutException, ExecutionException {
		StreamingJobFactory jobFactory = new StreamingJobFactory(_mockProcessFactory, new IniManager(_mockPropertiesUtil));

		LaunchStreamingJobMessage launchJobMessage = StreamingJobTestUtil.createLaunchMessage();

		StreamingJob job = jobFactory.createJob(launchJobMessage);

		job.startJob();

		Thread.sleep(1000);

		CompletableFuture<Void> jobCompleteFuture = job.stopJob();

		jobCompleteFuture.get(10, TimeUnit.SECONDS);  // Make sure no exceptions thrown
	}



	private static Answer<StreamingProcess> createProcess(String name) {
		return invocation -> {
			Path iniPath = invocation.getArgument(1);
			String[] cmdline = {
					"python", StreamingJobTestUtil.TEST_PROCESS_PATH, name, iniPath.toString() };
			ProcessBuilder builder = new ProcessBuilder(cmdline)
					.redirectErrorStream(true);
			return new StreamingProcess(name, builder, 1);
		};
	}
}

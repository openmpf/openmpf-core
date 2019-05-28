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

package org.mitre.mpf.wfm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.mpf.rest.api.node.EnvironmentVariableModel;
import org.mitre.mpf.wfm.service.component.ComponentLanguage;
import org.mitre.mpf.wfm.util.ObjectMapperFactory;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.mockito.InOrder;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestStreamingServiceManager {

	private StreamingServiceManagerImpl _streamingServiceManager;

	private PropertiesUtil _mockProperties;

	private ObjectMapper _objectMapper;

	private ObjectWriter _objectWriter;

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();


	@Before
	public void init() throws IOException {
		_mockProperties = mock(PropertiesUtil.class);

		_objectMapper = spy(ObjectMapperFactory.customObjectMapper());
		_objectWriter = spy(_objectMapper.writerWithDefaultPrettyPrinter());
		when(_objectMapper.writerWithDefaultPrettyPrinter())
				.thenReturn(_objectWriter);


		File streamingServicesFile = tempFolder.newFile("streamingServices.json");
		Files.write(streamingServicesFile.toPath(), Collections.singletonList("[]"));

		when(_mockProperties.getStreamingServices())
				.thenReturn(new FileSystemResource(streamingServicesFile));

		_streamingServiceManager = new StreamingServiceManagerImpl(_mockProperties, _objectMapper);
	}



	@Test
	public void canServicesLoadFromExistingFile() throws IOException {
		List<StreamingServiceModel> testModels = createTestModels();

		long initialSize = Files.size(_mockProperties.getStreamingServices().getFile().toPath());

		try (OutputStream outputStream = _mockProperties.getStreamingServices().getOutputStream()) {
			_objectMapper.writeValue(outputStream, testModels);
		}

		long sizeAfterSave = Files.size(_mockProperties.getStreamingServices().getFile().toPath());
		assertTrue(sizeAfterSave > initialSize);

		StreamingServiceManager manager = new StreamingServiceManagerImpl(_mockProperties, _objectMapper);
		assertServiceListEqual(testModels, manager.getServices());
	}


	@Test
	public void canAddNewService() throws IOException {
		assertTrue(_streamingServiceManager.getServices().isEmpty());
		InOrder inOrder = inOrder(_objectWriter);

		List<StreamingServiceModel> testModels = createTestModels();
		_streamingServiceManager.addService(testModels.get(0));
		verifyNumServicesSaved(inOrder, 1);


		List<StreamingServiceModel> loadedServiceModels = _streamingServiceManager.getServices();
		assertServicesEqual(testModels.get(0), loadedServiceModels.get(0));

		_streamingServiceManager.addService(testModels.get(1));

		loadedServiceModels = _streamingServiceManager.getServices();
		assertServiceListEqual(testModels, loadedServiceModels);

		verifyNumServicesSaved(inOrder, 2);
	}


	@Test
	public void canNotAddDuplicateService() throws IOException {
		List<StreamingServiceModel> testModels = createTestModels();
		_streamingServiceManager.addService(testModels.get(0));
		_streamingServiceManager.addService(testModels.get(1));
		verify(_objectWriter, times(2))
				.writeValue(any(OutputStream.class), any());


		assertServiceListEqual(testModels, _streamingServiceManager.getServices());

		StreamingServiceModel dupService = new StreamingServiceModel(
				testModels.get(0).getServiceName(), "algo", ComponentLanguage.CPP, "lib path",
				Collections.emptyList());

		try {
			_streamingServiceManager.addService(dupService);
			fail("Expected IllegalStateException");
		}
		catch (IllegalStateException ignored) {}

		verify(_objectWriter, times(2))
				.writeValue(any(OutputStream.class), any());

		assertServiceListEqual(testModels, _streamingServiceManager.getServices());

	}


	@Test
	public void canRemoveService() throws IOException {
		InOrder inOrder = inOrder(_objectWriter);

		List<StreamingServiceModel> testModels = createTestModels();
		_streamingServiceManager.addService(testModels.get(0));

		verifyNumServicesSaved(inOrder, 1);

		_streamingServiceManager.addService(testModels.get(1));
		verifyNumServicesSaved(inOrder, 2);

		_streamingServiceManager.deleteService("not a service");
		verifyNumServicesSaved(inOrder, 2);

		assertServiceListEqual(testModels, _streamingServiceManager.getServices());

		_streamingServiceManager.deleteService(testModels.get(1).getServiceName());
		verifyNumServicesSaved(inOrder, 1);


		assertServiceListEqual(Collections.singletonList(testModels.get(0)),
		                       _streamingServiceManager.getServices());
	}


	private static void assertServiceListEqual(Collection<StreamingServiceModel> expectedServices,
	                                           Collection<StreamingServiceModel> actualServices) {
		assertEquals(expectedServices.size(), actualServices.size());

		Map<String, StreamingServiceModel> actualIndex = actualServices.stream()
				.collect(toMap(StreamingServiceModel::getServiceName, Function.identity()));

		for (StreamingServiceModel expectedService : expectedServices) {
			StreamingServiceModel actualService = actualIndex.get(expectedService.getServiceName());
			assertServicesEqual(expectedService, actualService);
		}
	}


	private static void assertServicesEqual(StreamingServiceModel expectedService, StreamingServiceModel actualService) {
		assertEquals(expectedService.getServiceName(), actualService.getServiceName());
		assertEquals(expectedService.getAlgorithmName(), actualService.getAlgorithmName());
		assertEquals(expectedService.getSourceLanguage(), actualService.getSourceLanguage());

		assertEnvironmentVariablesEqual(expectedService.getEnvironmentVariables(),
		                                actualService.getEnvironmentVariables());
	}


	private static void assertEnvironmentVariablesEqual(Collection<EnvironmentVariableModel> expectedEnv,
	                                                    Collection<EnvironmentVariableModel> actualEnv) {
		assertEquals(expectedEnv.size(), actualEnv.size());

		Map<String, EnvironmentVariableModel> actualEnvIndex = actualEnv.stream()
				.collect(toMap(EnvironmentVariableModel::getName, Function.identity()));

		for (EnvironmentVariableModel expectedEnvVar : expectedEnv) {
			EnvironmentVariableModel actualEnvVar = actualEnvIndex.get(expectedEnvVar.getName());
			assertEquals(expectedEnvVar.getName(), actualEnvVar.getName());
			assertEquals(expectedEnvVar.getValue(), actualEnvVar.getValue());
			assertEquals(expectedEnvVar.getSep(), actualEnvVar.getSep());
		}
	}


	private void verifyNumServicesSaved(InOrder inOrder, int numSaved) throws IOException {
		inOrder.verify(_objectWriter)
				.writeValue(any(OutputStream.class), argThat(l -> ((Collection<?>) l).size() == numSaved));
	}


	private static List<StreamingServiceModel> createTestModels() {
		StreamingServiceModel serviceModel = new StreamingServiceModel(
				"theService",
				"theAlgorithm",
				ComponentLanguage.CPP,
				"lib/libmyMpfComponent.so",
				Arrays.asList(new EnvironmentVariableModel("var1", "val1", null),
				              new EnvironmentVariableModel("var2", "val2", null)));

		StreamingServiceModel serviceModel2 = new StreamingServiceModel(
				"theService2",
				"theAlgorithm2",
				ComponentLanguage.JAVA,
				"lib/myMpfComponent.jar",
				Arrays.asList(new EnvironmentVariableModel("sm2var1", "sm2val1", null),
				              new EnvironmentVariableModel("sm2var2", "sm2val2", null)));

		return Arrays.asList(serviceModel, serviceModel2);
	}


}

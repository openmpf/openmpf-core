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

package org.mitre.mpf.wfm.service.component;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.mpf.rest.api.component.ComponentState;
import org.mitre.mpf.rest.api.component.RegisterComponentModel;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static java.util.stream.Collectors.toList;
import static org.mitre.mpf.test.TestUtil.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class TestStartupComponentRegistrationService {


	@InjectMocks
	private StartupComponentRegistrationServiceImpl _startupRegisterSvc;

	private PropertiesUtil _mockPropertiesUtil;

	@Mock
	private ComponentDependencyFinder _mockDependencyFinder;

	@Mock
	private ComponentStateService _mockComponentStateSvc;

	@Mock
	private AddComponentService _mockAddComponentSvc;

	@Mock
	private StartupComponentServiceStarter _mockServiceStarter;

	@Rule
	public TemporaryFolder _componentUploadDir = new TemporaryFolder();

	@Rule
	public TemporaryFolder _pluginDeploymentDir = new TemporaryFolder();

	@Before
	public void init() throws IOException, ComponentRegistrationException {
		_componentUploadDir.newFolder("test");
		_componentUploadDir.newFile("bad.bad");

		_mockPropertiesUtil = mock(PropertiesUtil.class);
		when(_mockPropertiesUtil.getUploadedComponentsDirectory())
				.thenReturn(_componentUploadDir.getRoot());
		when(_mockPropertiesUtil.getPluginDeploymentPath())
				.thenReturn(_pluginDeploymentDir.getRoot().toPath());
		when(_mockPropertiesUtil.isStartupAutoRegistrationSkipped())
				.thenReturn(false);

		MockitoAnnotations.initMocks(this);

		when(_mockAddComponentSvc.registerComponent(notNull()))
				.thenAnswer(invocation -> {
					String arg = invocation.getArgument(0);
					String componentName = componentPackageToName(arg);

					RegisterComponentModel result = new RegisterComponentModel();
					result.setComponentState(ComponentState.REGISTERED);
					result.setComponentName(componentName);
					return result;
				});

		//noinspection unchecked
		when(_mockDependencyFinder.getRegistrationOrder(notNull()))
				.thenAnswer(invocation -> invocation.getArgument(0, Collection.class).stream()
						.sorted(Comparator.comparing(p -> ((Path) p).getFileName().toString().toLowerCase()))
						.collect(toList()));

	}



	@Test
	public void doesNothingWhenNoPackagesAndNoneRegistered() throws ComponentRegistrationException {
		_startupRegisterSvc.registerUnregisteredComponents();

		assertNoneRegistered();
	}


	@Test
	public void doesNothingWhenNoPackagesAndSomeRegistered() throws ComponentRegistrationException {
		when(_mockComponentStateSvc.get())
				.thenReturn(Arrays.asList(new RegisterComponentModel(), new RegisterComponentModel()));

		_startupRegisterSvc.registerUnregisteredComponents();

		assertNoneRegistered();
	}


	@Test
	public void doesNothingWhenAllPackagesRegistered() throws IOException, ComponentRegistrationException {
		List<Path> packages = addComponentPackages("Test1", "Test2", "Test3");

		List<RegisterComponentModel> components = packages.stream()
				.map(TestStartupComponentRegistrationService::createRegisteredComponent)
				.collect(toList());
		when(_mockComponentStateSvc.get())
				.thenReturn(components);

		_startupRegisterSvc.registerUnregisteredComponents();

		assertNoneRegistered();
	}



	@Test
	public void registersAllPackagesWhenNoneRegistered() throws IOException, ComponentRegistrationException {
		List<Path> packages = addComponentPackages("Test1", "Test2", "Test3");
		_startupRegisterSvc.registerUnregisteredComponents();

		assertExactRegistrationOrder(packages);
	}


	@Test
	public void onlyRegistersUnregisteredPackages() throws IOException, ComponentRegistrationException {
		List<Path> packages = addComponentPackages("Test1", "Test2", "Test3");
		List<RegisterComponentModel> registeredComponents = Arrays.asList(
				createRegisteredComponent(packages.get(1)),
		        createRegisteredComponent(Paths.get("/tmp/fake.tar.gz")));

		when(_mockComponentStateSvc.get())
				.thenReturn(registeredComponents);

		_startupRegisterSvc.registerUnregisteredComponents();
		assertExactRegistrationOrder(Arrays.asList(packages.get(0), packages.get(2)));
		assertRegistrationNotAttempted(packages.get(1));

		assertServicesStarted(Arrays.asList(packages.get(0), packages.get(2)));
		assertServiceNotStarted(packages.get(1));
	}


	@Test
	public void doesNotReRegisterComponentsInErrorState() throws IOException, ComponentRegistrationException {
		List<Path> packages = addComponentPackages("RegError", "Test2", "Test3");
		RegisterComponentModel errorRcm = createRegisterModel(packages.get(0), ComponentState.REGISTER_ERROR);
		when(_mockComponentStateSvc.get())
				.thenReturn(Collections.singletonList(errorRcm));

		_startupRegisterSvc.registerUnregisteredComponents();
		assertExactRegistrationOrder(packages.subList(1, 3));
		assertRegistrationNotAttempted(packages.get(0));

		assertServicesStarted(packages.subList(1, 3));
		assertServiceNotStarted(packages.get(0));
	}


	@Test
	public void addsRegisterErrorEntryWhenScriptFails() throws ComponentRegistrationException, IOException {
		List<Path> packages = addComponentPackages("Test1", "Test2", "fail");
		List<RegisterComponentModel> registeredComponents = Arrays.asList(
				createRegisteredComponent(packages.get(1)),
				createRegisteredComponent(Paths.get("/tmp/fake.tar.gz")));

		when(_mockComponentStateSvc.get())
				.thenReturn(registeredComponents);

		when(_mockDependencyFinder.getRegistrationOrder(any()))
				.thenThrow(IllegalStateException.class);


		_startupRegisterSvc.registerUnregisteredComponents();

		assertErrorEntryAdded(packages.get(0));
		assertErrorEntryAdded(packages.get(2));

		verify(_mockComponentStateSvc, never())
				.addRegistrationErrorEntry(packages.get(1));
		assertNoneRegistered();
	}


	@Test
	public void stopsRegistrationAfterFirstFailure() throws IOException, ComponentRegistrationException {
		List<Path> packages = addComponentPackages("ATest1", "RegError", "Test3");

		when(_mockAddComponentSvc.registerComponent(packages.get(1).getFileName().toString()))
				.thenThrow(ComponentRegistrationSubsystemException.class);


		_startupRegisterSvc.registerUnregisteredComponents();

		assertRegistrationOrder(packages.subList(0, 2));
		assertUploadedEntryAdded(packages.get(2));
		verify(_mockAddComponentSvc, never())
				.registerComponent(packages.get(2).getFileName().toString());

		assertServiceStarted(packages.get(0));
		assertServiceNotStarted(packages.get(1));
		assertServiceNotStarted(packages.get(2));
	}


	@Test
	public void canRegisterPreDeployedComponents() throws IOException, ComponentRegistrationException {
		List<Path> packages = addComponentPackages("Deployed", "NotDeployed");
		_pluginDeploymentDir.newFolder("Deployed", "descriptor");
		File deployedDescriptor = _pluginDeploymentDir.newFile("Deployed/descriptor/descriptor.json");

		_startupRegisterSvc.registerUnregisteredComponents();

		InOrder inOrder = inOrder(_mockComponentStateSvc, _mockAddComponentSvc);
		inOrder.verify(_mockComponentStateSvc)
				.addEntryForDeployedPackage(packages.get(0), deployedDescriptor.toPath());
		inOrder.verify(_mockAddComponentSvc)
				.registerComponent("Deployed.tar.gz");

		inOrder.verify(_mockComponentStateSvc)
				.addEntryForUploadedPackage(packages.get(1));
		inOrder.verify(_mockAddComponentSvc)
				.registerComponent("NotDeployed.tar.gz");
		inOrder.verifyNoMoreInteractions();
	}


	private void assertNoneRegistered() throws ComponentRegistrationException {
		verify(_mockComponentStateSvc, never())
				.addEntryForUploadedPackage(any());

		verify(_mockAddComponentSvc, never())
				.registerComponent(any());

		verify(_mockServiceStarter, never())
				.startServicesForComponents(nonEmptyCollection());
	}


	private void assertExactRegistrationOrder(List<Path> componentPackages) throws ComponentRegistrationException {
		assertRegistrationOrder(componentPackages)
				.verifyNoMoreInteractions();
	}


	private InOrder assertRegistrationOrder(Iterable<Path> componentPackages) throws ComponentRegistrationException {
		InOrder inOrder = inOrder(_mockComponentStateSvc, _mockAddComponentSvc);

		for (Path componentPackage : componentPackages) {
			inOrder.verify(_mockComponentStateSvc)
					.addEntryForUploadedPackage(componentPackage);

			inOrder.verify(_mockAddComponentSvc)
					.registerComponent(componentPackage.getFileName().toString());
		}
		return inOrder;
	}


	private void assertRegistrationNotAttempted(Path componentPackage) throws ComponentRegistrationException {
		verify(_mockComponentStateSvc, never())
				.addEntryForUploadedPackage(componentPackage);
		verify(_mockAddComponentSvc, never())
				.registerComponent(componentPackage.getFileName().toString());
	}


	private void assertErrorEntryAdded(Path componentPackage) {
		verify(_mockComponentStateSvc)
				.addRegistrationErrorEntry(componentPackage);
	}


	private void assertUploadedEntryAdded(Path componentPackage) {
		verify(_mockComponentStateSvc)
				.addEntryForUploadedPackage(componentPackage);
	}


	private void assertServicesStarted(Iterable<Path> componentPackages) {
		for (Path packagePath : componentPackages) {
			assertServiceStarted(packagePath);
		}
	}

	private void assertServiceStarted(Path componentPackage) {
		String name = componentPackageToName(componentPackage);
		verify(_mockServiceStarter)
				.startServicesForComponents(
						collectionContaining(rcm -> rcm.getComponentName().equals(name)));
	}

	private void assertServiceNotStarted(Path componentPackage) {
		String name = componentPackageToName(componentPackage);
		verify(_mockServiceStarter, never())
				.startServicesForComponents(
						collectionContaining(rcm -> rcm.getComponentName().equals(name)));
	}


	private List<Path> addComponentPackages(String... names) throws IOException {
		List<Path> results = new ArrayList<>();
		for (String name : names) {
			Path packagePath = _componentUploadDir.newFile(name + ".tar.gz").toPath();

			try (TarArchiveOutputStream outputStream
					     = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(packagePath)))) {
				ArchiveEntry entry = new TarArchiveEntry(name + "/descriptor");
				outputStream.putArchiveEntry(entry);
				outputStream.closeArchiveEntry();
			}
			results.add(packagePath);
		}
		return results;
	}


	private static RegisterComponentModel createRegisteredComponent(Path packagePath) {
		return createRegisterModel(packagePath, ComponentState.REGISTERED);
	}


	private static RegisterComponentModel createRegisterModel(Path packagePath, ComponentState state) {
		RegisterComponentModel result = new RegisterComponentModel();
		result.setFullUploadedFilePath(packagePath.toString());
		result.setJsonDescriptorPath("/tmp/" + packagePath.getFileName() + ".json");
		result.setComponentState(state);
		return result;
	}


	private static String componentPackageToName(String packageFileName) {
		return packageFileName.replace(".tar.gz", "");
	}

	private static String componentPackageToName(Path packagePath) {
		return componentPackageToName(packagePath.getFileName().toString());
	}
}

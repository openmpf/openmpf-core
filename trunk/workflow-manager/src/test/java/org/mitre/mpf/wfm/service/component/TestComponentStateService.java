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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mitre.mpf.rest.api.component.ComponentState;
import org.mitre.mpf.rest.api.component.RegisterComponentModel;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.mockito.*;
import org.springframework.core.io.WritableResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toList;
import static org.mockito.Mockito.*;

public class TestComponentStateService {

    @InjectMocks
    private ComponentStateServiceImpl _componentStateService;

    @Mock
    private PropertiesUtil _mockProperties;

    @Mock
    private ObjectMapper _mockObjectMapper;

    private static final String _testPackageName = "TestComponent.tar.gz";
    private static final String _testComponentName = "TestComponent";

    private RegisterComponentModel _testRegisterModel;


    @Before
    public void init() throws IOException {
        MockitoAnnotations.initMocks(this);

        _testRegisterModel = new RegisterComponentModel();
        _testRegisterModel.setComponentName(_testComponentName);
        _testRegisterModel.setPackageFileName(_testPackageName);
        _testRegisterModel.setComponentState(ComponentState.REGISTERED);
        _testRegisterModel.setFullUploadedFilePath(Paths.get(_testPackageName).toAbsolutePath().toString());

        RegisterComponentModel otherRcm = new RegisterComponentModel();
        otherRcm.setComponentName("OtherComponentName");

        RegisterComponentModel otherRcm2 = new RegisterComponentModel();
        otherRcm2.setPackageFileName("Whatever.tar.gz");


        when(_mockObjectMapper.readValue((InputStream) any(), any(TypeReference.class)))
                .thenReturn(Lists.newArrayList(otherRcm, _testRegisterModel, otherRcm2));

        when(_mockProperties.getComponentInfoFile())
                .thenReturn(mock(WritableResource.class));
    }


    @Test
    public void canGetAllModels() {
        List<RegisterComponentModel> results = _componentStateService.get();

        Assert.assertEquals(1, results.stream()
                .filter(rcm -> _testComponentName.equals(rcm.getComponentName()))
                .count());

        Assert.assertEquals(3, results.size());
    }


    @Test
    public void canGetByPackageFile() {
        assertCorrectModel(_componentStateService.getByPackageFile(_testPackageName));
    }

    @Test
    public void returnsEmptyWhenNoMatchingPackageName() {
        Assert.assertFalse(_componentStateService.getByPackageFile("foo").isPresent());
    }

    @Test
    public void canGetByComponentName() {
        assertCorrectModel(_componentStateService.getByComponentName(_testComponentName));
    }

    @Test
    public void returnsEmptyWhenNoMatchingComponentName() {
        Assert.assertFalse(_componentStateService.getByComponentName("foo").isPresent());

    }


    @SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "OptionalGetWithoutIsPresent"})
    private void assertCorrectModel(Optional<RegisterComponentModel> rcm) {
        Assert.assertTrue(rcm.isPresent());
        Assert.assertEquals(_testRegisterModel.getComponentName(), rcm.get().getComponentName());
        Assert.assertEquals(_testRegisterModel.getPackageFileName(), rcm.get().getPackageFileName());
    }


    @Test
    public void canUpdate() throws IOException {
        RegisterComponentModel modelToUpdate = new RegisterComponentModel();
        modelToUpdate.setComponentName(_testComponentName);
        modelToUpdate.setComponentState(ComponentState.REGISTER_ERROR);

        _componentStateService.update(modelToUpdate);
        verifySavedModelList(ms -> ms.size() == 3
                && getTargetModel(ms).getComponentState() == ComponentState.REGISTER_ERROR);
    }


    @Test
    public void canReplacePackageState() throws IOException {
        ComponentState oldState = _componentStateService.replacePackageState(_testPackageName, ComponentState.REGISTER_ERROR);

        verifySavedModelList(ms -> ms.size() == 3
                && getTargetModel(ms).getComponentState() == ComponentState.REGISTER_ERROR);

        Assert.assertEquals(ComponentState.REGISTERED, oldState);
    }


    @Test
    public void canReplaceComponentState() throws IOException {
        _componentStateService.replaceComponentState(_testComponentName, ComponentState.REGISTER_ERROR);
        verifySavedModelList(ms -> ms.size() == 3
                && getTargetModel(ms).getComponentState() == ComponentState.REGISTER_ERROR);
    }


    @Test
    public void canRemoveComponent() throws IOException {
        _componentStateService.removeComponent(_testComponentName);
        verifySavedModelList(ms -> ms.size() == 2 && getTargetModel(ms) == null);
    }


    @Test
    public void canRemovePackage() throws IOException {
        _componentStateService.removePackage(_testPackageName);
        verifySavedModelList(ms -> ms.size() == 2 && getTargetModel(ms) == null);
    }


    @Test
    public void canAddEntryForUploadedPackage() throws IOException {
        String newPackage = "NewComponent.tar.gz";
        _componentStateService.addEntryForUploadedPackage(Paths.get(newPackage));

        verifySavedModelList(ms -> ms.size() == 4 &&
                getModel(newPackage, ms).getComponentState() == ComponentState.UPLOADED);
    }


    @Test(expected = IllegalStateException.class)
    public void throwsExceptionWhenAddingDuplicateModel() {
        _componentStateService.addEntryForUploadedPackage(Paths.get(_testPackageName));
    }

    @Test
    public void canAddRegistrationErrorEntryForUploadedPackage() throws IOException {
        String newPackage = "NewComponent.tar.gz";
        Path newPackagePath = Paths.get(newPackage);
        _componentStateService.addRegistrationErrorEntry(newPackagePath);

        verifySavedModelList(ms -> {
            RegisterComponentModel rcm = getModel(newPackage, ms);
            return ms.size() == 4
                    && rcm.getComponentState() == ComponentState.REGISTER_ERROR
                    && rcm.getFullUploadedFilePath().equals(newPackagePath.toAbsolutePath().toString());
        });
    }

    @Test
    public void canAddUploadErrorEntry() throws IOException {
        String newPackage = "NewComponent.tar.gz";
        _componentStateService.addUploadErrorEntry(newPackage);

        verifySavedModelList(ms -> ms.size() == 4
                && getModel(newPackage, ms).getComponentState() == ComponentState.UPLOAD_ERROR);

    }


    private void verifySavedModelList(Predicate<List<RegisterComponentModel>> predicate) throws IOException {
        verify(_mockObjectMapper)
                .writeValue((OutputStream) any(),
                            argThat(objs -> predicate.test((List<RegisterComponentModel>) objs)));
    }



    private static RegisterComponentModel getTargetModel(List<RegisterComponentModel> models) {
        List<RegisterComponentModel> matchingModels = models
                .stream()
                .filter(rcm -> _testComponentName.equals(rcm.getComponentName()))
                .collect(toList());

        Assert.assertTrue(matchingModels.size() < 2);
        return matchingModels
                .stream()
                .findAny()
                .orElse(null);
    }


    private static RegisterComponentModel getModel(String packageName, List<RegisterComponentModel> models) {
        List<RegisterComponentModel> matchingModels = models
                .stream()
                .filter(rcm -> packageName.equals(rcm.getPackageFileName()))
                .collect(toList());

        Assert.assertTrue(matchingModels.size() < 2);
        return matchingModels
                .stream()
                .findAny()
                .orElse(null);

    }
}

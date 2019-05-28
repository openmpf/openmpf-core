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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mitre.mpf.rest.api.component.ComponentState;
import org.mitre.mpf.rest.api.component.RegisterComponentModel;
import org.mitre.mpf.rest.api.node.NodeManagerModel;
import org.mitre.mpf.rest.api.node.ServiceModel;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.pipeline.xml.*;
import org.mitre.mpf.wfm.service.NodeManagerService;
import org.mitre.mpf.wfm.service.PipelineService;
import org.mitre.mpf.wfm.service.StreamingServiceManager;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.collections.Sets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mitre.mpf.test.TestUtil.eqIgnoreCase;
import static org.mitre.mpf.wfm.service.component.TestDescriptorConstants.COMPONENT_NAME;
import static org.mitre.mpf.wfm.service.component.TestDescriptorConstants.DESCRIPTOR_PATH;
import static org.mockito.Mockito.*;

public class TestRemoveComponentService {

    @InjectMocks
    private RemoveComponentServiceImpl _removeComponentService;

    @Mock
    private NodeManagerService _mockNodeManager;

    @Mock
    private StreamingServiceManager _mockStreamingServiceManager;

    @Mock
    private ComponentDeploymentService _mockDeploymentService;

    @Mock
    private ComponentStateService _mockStateService;

    @Mock
    private PipelineService _mockPipelineService;

    @Rule
    public TemporaryFolder _tempDir = new TemporaryFolder();

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testRemoveComponentHappyPath() throws IOException {
        // Arrange
        JsonComponentDescriptor descriptor = TestDescriptorFactory.get();
        String serviceName = descriptor.algorithm.name;
        String algoName = descriptor.algorithm.name.toUpperCase();

        RegisterComponentModel rcm = new RegisterComponentModel();
        rcm.setComponentState(ComponentState.REGISTERED);
        rcm.setComponentName(COMPONENT_NAME);
        rcm.setServiceName(serviceName);
        rcm.setStreamingServiceName(serviceName);
        rcm.setAlgorithmName(algoName);
        rcm.setJsonDescriptorPath(DESCRIPTOR_PATH);

        ServiceModel serviceModel = new ServiceModel();
        serviceModel.setServiceName(serviceName);
        ServiceModel serviceModel2 = new ServiceModel();
        serviceModel2.setServiceName("Other service");

        NodeManagerModel nodeManagerModel = new NodeManagerModel();
        nodeManagerModel.getServices().add(serviceModel);
        nodeManagerModel.getServices().add(serviceModel2);

        NodeManagerModel nodeManagerModel2 = new NodeManagerModel();
        nodeManagerModel2.getServices().add(serviceModel2);
        List<NodeManagerModel> nodeManagerModels = Arrays.asList(nodeManagerModel, nodeManagerModel2);

        when(_mockNodeManager.getNodeManagerModels())
                .thenReturn(nodeManagerModels);

        when(_mockStateService.getByComponentName(COMPONENT_NAME))
                .thenReturn(Optional.of(rcm));

        ActionDefinition actionDef = new ActionDefinition("action1-name", algoName, "");
        ActionDefinition actionDef2 = new ActionDefinition("action2-name", "foo", "");
        when(_mockPipelineService.getActions())
                .thenReturn(Sets.newSet(actionDef, actionDef2));

        TaskDefinition taskDef = new TaskDefinition("task1-name", "");
        taskDef.getActions().add(new ActionDefinitionRef(actionDef.getName()));
        when(_mockPipelineService.getTasks())
                .thenReturn(Sets.newSet(taskDef, new TaskDefinition("asdf", "")));

        PipelineDefinition pipelineDef = new PipelineDefinition("pipeline1-name", "");
        pipelineDef.getTaskRefs().add(new TaskDefinitionRef(taskDef.getName()));

        when(_mockPipelineService.getPipelines())
                .thenReturn(Sets.newSet(pipelineDef, new PipelineDefinition("sdaf", "")));
        // Act
        _removeComponentService.removeComponent(COMPONENT_NAME);

        // Assert

        verify(_mockStateService)
                .removeComponent(COMPONENT_NAME);

        verify(_mockNodeManager)
                .saveAndReloadNodeManagerConfig(argThat(nodes -> nodes.contains(nodeManagerModel)
                        && nodes.contains(nodeManagerModel2)
                        && !nodeManagerModel.getServices().contains(serviceModel)
                        && nodeManagerModel.getServices().contains(serviceModel2)));
        verify(_mockNodeManager)
                .removeService(serviceName);

        verify(_mockStreamingServiceManager)
                .deleteService(serviceName);

        verify(_mockPipelineService)
                .deleteAlgorithm(algoName);

        verify(_mockPipelineService)
                .deleteAction(actionDef.getName());

        verify(_mockPipelineService)
                .deleteTask(taskDef.getName());

        verify(_mockPipelineService)
                .deletePipeline(pipelineDef.getName());

        verify(_mockDeploymentService)
                .undeployComponent(COMPONENT_NAME);

        verify(_mockStateService)
                .removeComponent(COMPONENT_NAME);
    }


    @Test
    public void testRemoveUnmanagedComponent() throws IOException {
        Path pluginsDir = _tempDir.newFolder("plugins").toPath();
        Path otherPluginDir = pluginsDir.resolve("other-plugin");
        Files.createDirectory(otherPluginDir);

        Path testComponentDir = pluginsDir.resolve(COMPONENT_NAME);
        Path testDescriptorDir = testComponentDir.resolve("descriptor");
        Files.createDirectories(testDescriptorDir);
        Path testDescriptor = testDescriptorDir.resolve("descriptor.json");
        Files.write(testDescriptor, Collections.singletonList("hello world"));


        RegisterComponentModel rcm = new RegisterComponentModel();
        rcm.setJsonDescriptorPath(testDescriptor.toString());
        rcm.setManaged(false);
        rcm.setComponentName(COMPONENT_NAME);
        rcm.setAlgorithmName("TEST ALGO");
        rcm.setPipelines(Collections.singletonList("TEST PIPELINE"));
        when(_mockStateService.getByComponentName(COMPONENT_NAME))
                .thenReturn(Optional.of(rcm));

        _removeComponentService.removeComponent(COMPONENT_NAME);

        verify(_mockStateService)
                .removeComponent(COMPONENT_NAME);
        // Pipeline deletion is more extensively tested in testRecursiveDelete
        verify(_mockPipelineService)
                .deleteAlgorithm("TEST ALGO");
        verify(_mockPipelineService)
                .deletePipeline("TEST PIPELINE");

        verifyZeroInteractions(_mockNodeManager, _mockStreamingServiceManager, _mockDeploymentService);

        assertTrue(Files.exists(otherPluginDir));
        assertFalse(Files.exists(testComponentDir));
    }


    @Test
    public void testRecursiveDelete() throws WfmProcessingException {
        // Arrange
        String componentAlgoName = "Component Algo Name";

        String actionName = "Action Name";
        ActionDefinition action = new ActionDefinition(actionName, componentAlgoName, "a description");
        ActionDefinitionRef actionRef = new ActionDefinitionRef(actionName);

        String taskName = "TASK NAME";
        TaskDefinition task = new TaskDefinition(taskName, "t description");
        task.getActions().add(actionRef);
        TaskDefinitionRef taskRef = new TaskDefinitionRef(taskName);

        String pipelineName = "PIPELINE NAME";
        PipelineDefinition pipeline = new PipelineDefinition(pipelineName, "p description");
        pipeline.getTaskRefs().add(taskRef);


        String externalAlgoName = "EXTERNAL ALGO NAME";

        String externalActionName = "EXTERNAL ACTION";
        ActionDefinition externalAction = new ActionDefinition(externalActionName, externalAlgoName,
                "a description");
        ActionDefinitionRef externalActionRef = new ActionDefinitionRef(externalActionName);

        String componentTaskName = "Component Task Name";
        TaskDefinition componentTask = new TaskDefinition(componentTaskName, "t description");
        componentTask.getActions().add(externalActionRef);
        TaskDefinitionRef componentTaskRef = new TaskDefinitionRef(componentTaskName);

        String externalPipelineName = "External Pipeline Name";
        PipelineDefinition externalPipeline = new PipelineDefinition(externalPipelineName, "p description");
        externalPipeline.getTaskRefs().add(componentTaskRef);

        String componentActionName = "Component Action Name";
        String componentPipelineName = "Component Pipeline Name";

        RegisterComponentModel rcm = new RegisterComponentModel();
        rcm.setAlgorithmName(componentAlgoName);
        rcm.getTasks().add(componentTaskName);
        rcm.getActions().add(componentActionName);
        rcm.getPipelines().add(componentPipelineName);



        when(_mockPipelineService.getPipelines())
                .thenReturn(Sets.newSet(pipeline, externalPipeline));

        when(_mockPipelineService.getActions())
                .thenReturn(Sets.newSet(action, externalAction));

        when(_mockPipelineService.getTasks())
                .thenReturn(Sets.newSet(task, componentTask));

        // Act
        _removeComponentService.deleteCustomPipelines(rcm, true);


        verify(_mockPipelineService)
                .deleteAlgorithm(componentAlgoName.toUpperCase());

        verify(_mockPipelineService, never())
                .deleteAlgorithm(externalAlgoName.toUpperCase());


        verify(_mockPipelineService)
                .deleteAction(actionName.toUpperCase());
        verify(_mockPipelineService)
                .deleteAction(componentActionName.toUpperCase());

        verify(_mockPipelineService, never())
                .deleteAction(eqIgnoreCase(externalActionName));

        verify(_mockPipelineService)
                .deleteTask(taskName);
        verify(_mockPipelineService)
                .deleteTask(componentTaskName.toUpperCase());

        verify(_mockPipelineService)
                .deletePipeline(pipelineName.toUpperCase());
        verify(_mockPipelineService)
                .deletePipeline(externalPipelineName.toUpperCase());
        verify(_mockPipelineService)
                .deletePipeline(componentPipelineName.toUpperCase());
    }
}


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


package org.mitre.mpf.mvc.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.mitre.mpf.rest.api.MessageModel;
import org.mitre.mpf.rest.api.pipelines.*;
import org.mitre.mpf.wfm.service.WorkflowPropertyService;
import org.mitre.mpf.wfm.service.pipeline.InvalidPipelineException;
import org.mitre.mpf.wfm.service.pipeline.PipelineService;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.inject.Inject;
import java.util.*;

import static java.util.stream.Collectors.toList;

@Api("Pipelines")
@Scope("request")
@RestController
@RequestMapping(produces = "application/json")
// Methods to get single pipeline element don't show up in Swagger because Swagger won't display both
// /rest/elements and /rest/elements?name={name}. We decided to just show the /rest/elements endpoints since they
// return the same models as the /rest/elements?name endpoints, except that they are in a list.
// Github issue: https://github.com/swagger-api/swagger-ui/issues/2031
// Appears to fixed in Swagger version 3.
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    private final PropertiesUtil _propertiesUtil;

    private final WorkflowPropertyService _workflowPropertyService;

    private final PipelineService _pipelineService;

    @Inject
    PipelineController(
            PropertiesUtil propertiesUtil,
            WorkflowPropertyService workflowPropertyService,
            PipelineService pipelineService) {
        _propertiesUtil = propertiesUtil;
        _workflowPropertyService = workflowPropertyService;
        _pipelineService = pipelineService;
    }


    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidPipelineException.class)
    @ResponseBody
    public MessageModel invalidPipelineHandler(InvalidPipelineException ex) {
        log.error(ex.getMessage(), ex);
        return new MessageModel(ex.getMessage());
    }



    @RequestMapping(value = {  "/pipelines", "/rest/pipelines" }, method = RequestMethod.GET)
    @ApiOperation("Retrieves list of available pipelines.")
    public List<Pipeline> getPipelines() {
        return _pipelineService.getPipelines();
    }


    @RequestMapping(value = { "/pipelines", "/rest/pipelines" },
            method = RequestMethod.GET,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiResponses(@ApiResponse(code = 404, message = "Not found"))
    public ResponseEntity<Pipeline> getPipeline(String name) {
        return Optional.ofNullable(_pipelineService.getPipeline(name))
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    @RequestMapping(value = { "/pipelines", "/rest/pipelines" }, method = RequestMethod.POST)
    @ApiOperation("Adds a new pipeline.")
    @ApiResponses(@ApiResponse(code = 400, message = "Invalid request", response = MessageModel.class))
    public void add(@RequestBody Pipeline pipeline) {
        _pipelineService.save(pipeline);
    }


    @RequestMapping(value = { "/pipelines", "/rest/pipelines" },
            method = RequestMethod.DELETE,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiOperation("Deletes a pipeline.")
    public void deletePipeline(String name) {
        _pipelineService.deletePipeline(name);
    }




    @RequestMapping(value = { "/tasks", "/rest/tasks" }, method = RequestMethod.GET)
    @ApiOperation("Retrieves list of available tasks.")
    public List<Task> getTasks() {
        return _pipelineService.getTasks();
    }


    @RequestMapping(value = { "/tasks", "/rest/tasks" },
            method = RequestMethod.GET,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiResponses(@ApiResponse(code = 404, message = "Not found"))
    public ResponseEntity<Task> getTask(String name) {
        return Optional.ofNullable(_pipelineService.getTask(name))
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    @RequestMapping(value = { "/tasks", "/rest/tasks" }, method = RequestMethod.POST)
    @ApiOperation("Adds a new task.")
    @ApiResponses(@ApiResponse(code = 400, message = "Invalid request", response = MessageModel.class))
    public void add(@RequestBody Task task) {
        _pipelineService.save(task);
    }


    @RequestMapping(value = { "/tasks", "/rest/tasks" },
            method = RequestMethod.DELETE,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiOperation("Deletes a task.")
    public void deleteTask(String name) {
        _pipelineService.deleteTask(name);
    }





    @RequestMapping(value = { "/actions", "/rest/actions" }, method = RequestMethod.GET)
    @ApiOperation("Retrieves list of available actions.")
    public List<Action> getActions() {
        return _pipelineService.getActions();
    }


    @RequestMapping(value = { "/actions", "/rest/actions" },
            method = RequestMethod.GET,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiResponses(@ApiResponse(code = 404, message = "Not found"))
    public ResponseEntity<Action> getAction(String name) {
        return Optional.ofNullable(_pipelineService.getAction(name))
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    @RequestMapping(value = { "/actions", "/rest/actions" }, method = RequestMethod.POST)
    @ApiOperation("Adds a new action.")
    @ApiResponses(@ApiResponse(code = 400, message = "Invalid request", response = MessageModel.class))
    public void add(@RequestBody Action action) {
        _pipelineService.save(action);
    }


    @RequestMapping(value = { "/actions", "/rest/actions" },
            method = RequestMethod.DELETE,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiOperation("Deletes an action.")
    public void deleteAction(String name) {
        _pipelineService.deleteAction(name);
    }




    @RequestMapping(value = { "/algorithms", "/rest/algorithms" }, method = RequestMethod.GET)
    @ApiOperation("Retrieves list of available algorithms.")
    public List<Algorithm> getAlgorithms() {
        return _pipelineService.getAlgorithms()
                .stream()
                .map(this::getAlgoWithDefaultValuesSet)
                .collect(toList());
    }


    @RequestMapping(value = { "/algorithms", "/rest/algorithms" },
            method = RequestMethod.GET,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiResponses(@ApiResponse(code = 404, message = "Not found"))
    public ResponseEntity<Algorithm> getAlgorithm(String name) {
        return Optional.ofNullable(_pipelineService.getAlgorithm(name))
                .map(this::getAlgoWithDefaultValuesSet)
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    private Algorithm getAlgoWithDefaultValuesSet(Algorithm algorithm) {
        var propsWithDefaultSet = new ArrayList<AlgorithmProperty>();
        var propertiesAdded = new HashSet<String>();

        for (var property : algorithm.getProvidesCollection().getProperties()) {
            if (property.getDefaultValue() != null) {
                propsWithDefaultSet.add(property);
            }
            else {
                var propWithDefault = new AlgorithmProperty(
                        property.getName(), property.getDescription(), property.getType(),
                        _propertiesUtil.lookup(property.getPropertiesKey()), property.getPropertiesKey());
                propsWithDefaultSet.add(propWithDefault);
            }
            propertiesAdded.add(property.getName());
        }

        if (algorithm.getActionType() == ActionType.DETECTION) {
            addWorkflowProperties(propertiesAdded, propsWithDefaultSet);
        }

        return new Algorithm(
                algorithm.getName(), algorithm.getDescription(), algorithm.getActionType(),
                algorithm.getRequiresCollection(),
                new Algorithm.Provides(algorithm.getProvidesCollection().getStates(), propsWithDefaultSet),
                algorithm.getSupportsBatchProcessing(), algorithm.getSupportsStreamProcessing());
    }


    private void addWorkflowProperties(Collection<String> propertiesAlreadyAdded,
                                       Collection<AlgorithmProperty> propsWithDefaultSet) {

        for (var workflowProperty : _workflowPropertyService.getProperties()) {
            if (!propertiesAlreadyAdded.contains(workflowProperty.getName())) {
                var workflowPropVal = _workflowPropertyService.getPropertyValue(workflowProperty.getName());
                if (workflowPropVal != null) {
                    propsWithDefaultSet.add(
                            new AlgorithmProperty(workflowProperty.getName(), workflowProperty.getDescription(),
                                                  workflowProperty.getType(), workflowPropVal,
                                                  workflowProperty.getPropertiesKey()));
                }
            }
        }
    }
}

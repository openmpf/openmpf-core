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
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.Algorithm;
import org.mitre.mpf.rest.api.pipelines.Pipeline;
import org.mitre.mpf.rest.api.pipelines.Task;
import org.mitre.mpf.wfm.pipeline.InvalidPipelineException;
import org.mitre.mpf.wfm.pipeline.PipelineService;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@Api("Pipelines")
@Scope("request")
@Profile("website")
@RestController
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);


    private final PropertiesUtil _propertiesUtil;

    private final PipelineService _pipelineService;

    @Inject
    PipelineController(
            PropertiesUtil propertiesUtil,
            PipelineService pipelineService) {
        _propertiesUtil = propertiesUtil;
        _pipelineService = pipelineService;
    }


    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidPipelineException.class)
    @ResponseBody
    public Map<String, String> invalidPipelineHandler(InvalidPipelineException ex) {
        log.error(ex.getMessage(), ex);
        return Map.of("message", ex.getMessage());
    }



    @RequestMapping(value = {  "/pipelines", "/rest/pipelines" }, method = RequestMethod.GET)
    @ApiOperation("Retrieves list of available pipelines.")
    public List<Pipeline> getPipelines() {
        return List.copyOf(_pipelineService.getPipelines());
    }


    @RequestMapping(value = { "/pipelines", "/rest/pipelines" },
            method = RequestMethod.GET,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    public ResponseEntity<Pipeline> getPipeline(String name) {
        return Optional.ofNullable(_pipelineService.getPipeline(name))
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    @RequestMapping(value = { "/pipelines", "/rest/pipelines" }, method = RequestMethod.POST)
    @ApiOperation("Adds a new pipeline.")
    public void add(@RequestBody Pipeline pipeline) {
        _pipelineService.save(pipeline);
    }


    @RequestMapping(value = { "/pipelines", "/rest/pipelines" },
            method = RequestMethod.DELETE,
            produces = "application/json",
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiOperation("Deletes a pipeline.")
    public void deletePipeline(String name) {
        _pipelineService.deletePipeline(name);
    }




    @RequestMapping(value = { "/tasks", "/rest/tasks" }, method = RequestMethod.GET)
    @ApiOperation("Retrieves list of available tasks.")
    public List<Task> getTasks() {
        return List.copyOf(_pipelineService.getTasks());
    }


    @RequestMapping(value = { "/tasks", "/rest/tasks" },
            method = RequestMethod.GET,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    public ResponseEntity<Task> getTask(String name) {
        return Optional.ofNullable(_pipelineService.getTask(name))
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    @RequestMapping(value = { "/tasks", "/rest/tasks" }, method = RequestMethod.POST)
    @ApiOperation("Adds a new task.")
    public void add(@RequestBody Task task) {
        _pipelineService.save(task);
    }


    @RequestMapping(value = { "/tasks", "/rest/tasks" },
            method = RequestMethod.DELETE,
            produces = "application/json",
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    @ApiOperation("Deletes a task.")
    public void deleteTask(String name) {
        _pipelineService.deleteTask(name);
    }





    @RequestMapping(value = { "/actions", "/rest/actions" }, method = RequestMethod.GET)
    @ApiOperation("Retrieves list of available actions.")
    public List<Action> getActions() {
        return List.copyOf(_pipelineService.getActions());
    }


    @RequestMapping(value = { "/actions", "/rest/actions" },
            method = RequestMethod.GET,
            // Uses query string parameter instead of path variable to support names with special characters.
            params = "name")
    public ResponseEntity<Action> getAction(String name) {
        return Optional.ofNullable(_pipelineService.getAction(name))
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    @RequestMapping(value = { "/actions", "/rest/actions" }, method = RequestMethod.POST)
    @ApiOperation("Adds a new action.")
    public void add(@RequestBody Action action) {
        _pipelineService.save(action);
    }


    @RequestMapping(value = { "/actions", "/rest/actions" },
            method = RequestMethod.DELETE,
            produces = "application/json",
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
    public ResponseEntity<Algorithm> getAlgorithm(String name) {
        return Optional.ofNullable(_pipelineService.getAlgorithm(name))
                .map(this::getAlgoWithDefaultValuesSet)
                .map(p -> new ResponseEntity<>(p, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }


    private Algorithm getAlgoWithDefaultValuesSet(Algorithm algorithm) {
        var propsWithDefaultSet = new ArrayList<Algorithm.Property>();
        for (Algorithm.Property property : algorithm.getProvidesCollection().getProperties()) {
            if (property.getDefaultValue() != null) {
                propsWithDefaultSet.add(property);
            }
            else {
                var propWithDefault = new Algorithm.Property(
                        property.getName(), property.getDescription(), property.getType(),
                        _propertiesUtil.lookup(property.getPropertiesKey()), property.getPropertiesKey());
                propsWithDefaultSet.add(propWithDefault);
            }
        }

        return new Algorithm(
                algorithm.getName(), algorithm.getDescription(), algorithm.getActionType(),
                algorithm.getRequiresCollection(),
                new Algorithm.Provides(algorithm.getProvidesCollection().getStates(), propsWithDefaultSet),
                algorithm.getSupportsBatchProcessing(), algorithm.getSupportsStreamProcessing());
    }
}

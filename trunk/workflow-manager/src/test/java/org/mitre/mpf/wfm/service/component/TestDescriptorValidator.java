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
import org.junit.Test;
import org.mitre.mpf.wfm.enums.ActionType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestDescriptorValidator {

    private ComponentDescriptorValidator _validator;

    @Before
    public void init() {
        LocalValidatorFactoryBean springValidator = new LocalValidatorFactoryBean();
        springValidator.afterPropertiesSet();
        _validator = new ComponentDescriptorValidatorImpl(springValidator);
    }

    @Test
    public void doesNotThrowExceptionWhenDescriptorNoViolations() throws InvalidComponentDescriptorException {
        Validator mockSpringValidator = mock(Validator.class);
        when(mockSpringValidator.validate(notNull()))
                .thenReturn(Collections.emptySet());

        ComponentDescriptorValidator descriptorValidator = new ComponentDescriptorValidatorImpl(mockSpringValidator);
        descriptorValidator.validate(new JsonComponentDescriptor());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void throwsExceptionWhenConstraintViolations() {
        Validator mockSpringValidator = mock(Validator.class);
        ConstraintViolation violation = mock(ConstraintViolation.class);

        when(mockSpringValidator.validate(notNull()))
                .thenReturn(Collections.singleton(violation));

        ComponentDescriptorValidator descriptorValidator = new ComponentDescriptorValidatorImpl(mockSpringValidator);
        try {
            descriptorValidator.validate(new JsonComponentDescriptor());
            fail();
        }
        catch (InvalidComponentDescriptorException ex) {
            assertEquals(1, ex.getValidationErrors().size());
            assertTrue(ex.getValidationErrors().contains(violation));
        }
    }

    @Test
    public void canValidateBlankString() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertValidationErrors(descriptor, isEmpty("componentName"));

        descriptor.componentName = "";
        assertValidationErrors(descriptor, isEmpty("componentName"));

        descriptor.componentName = "hello";
        assertFieldValid(descriptor, "componentName");
    }


    @Test
    public void canValidateComponentLanguage() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertValidationErrors(descriptor, "sourceLanguage must be java, c++, or python");

        descriptor.sourceLanguage = ComponentLanguage.forValue("java");
        assertFieldValid(descriptor, "sourceLanguage");

        descriptor.sourceLanguage = ComponentLanguage.forValue("jaVa");
        assertFieldValid(descriptor, "sourceLanguage");

        descriptor.sourceLanguage = ComponentLanguage.forValue("c++");
        assertFieldValid(descriptor, "sourceLanguage");

        descriptor.sourceLanguage = ComponentLanguage.forValue("C++");
        assertFieldValid(descriptor, "sourceLanguage");

        descriptor.sourceLanguage = ComponentLanguage.forValue("Python");
        assertFieldValid(descriptor, "sourceLanguage");

        descriptor.sourceLanguage = ComponentLanguage.forValue("python");
        assertFieldValid(descriptor, "sourceLanguage");
    }


    @Test
    public void canValidateLibraryPath() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertValidationErrors(descriptor, doesNotSupportBatchOrStream());

        descriptor.streamLibrary = "/path/to/stream/lib.so";
        descriptor.batchLibrary = null;
        assertNoValidationErrors(descriptor, doesNotSupportBatchOrStream());

        descriptor.streamLibrary = null;
        descriptor.batchLibrary = "/path/to/batch/lib.so";
        assertNoValidationErrors(descriptor, doesNotSupportBatchOrStream());

        descriptor.streamLibrary = "/path/to/stream/lib.so";
        descriptor.batchLibrary = "/path/to/batch/lib.so";
        assertNoValidationErrors(descriptor, doesNotSupportBatchOrStream());
    }


    @Test
    public void canValidateCustomPipeline() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertFieldValid(descriptor, "pipelines");

        descriptor.pipelines = new ArrayList<>();
        assertFieldValid(descriptor, "pipelines");

        JsonComponentDescriptor.Pipeline pipeline = new JsonComponentDescriptor.Pipeline();
        descriptor.pipelines.add(pipeline);
        assertValidationErrors(descriptor,
                isEmpty("pipelines[0].name"),
                isEmpty("pipelines[0].description"),
                isEmpty("pipelines[0].tasks"));

        pipeline.tasks = new ArrayList<>();
        assertValidationErrors(descriptor, isEmpty("pipelines[0].tasks"));

        pipeline.tasks.add(null);
        pipeline.tasks.add("");
        pipeline.tasks.add("hello");

        assertValidationErrors(descriptor,
                isEmpty("pipelines[0].tasks[0]"),
                isEmpty("pipelines[0].tasks[1]"));
        assertFieldValid(descriptor, "pipelines[0].tasks[2]");
    }


    @Test
    public void canValidateCustomTask() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertFieldValid(descriptor, "tasks");

        descriptor.tasks = new ArrayList<>();
        assertFieldValid(descriptor, "tasks");

        JsonComponentDescriptor.Task task = new JsonComponentDescriptor.Task();
        descriptor.tasks.add(task);
        assertValidationErrors(descriptor,
                isEmpty("tasks[0].name"),
                isEmpty("tasks[0].description"),
                isEmpty("tasks[0].actions"));

        task.actions = new ArrayList<>();
        assertValidationErrors(descriptor, isEmpty("tasks[0].actions"));

        task.actions.add(null);
        task.actions.add("");
        task.actions.add("hello");

        assertValidationErrors(descriptor,
                isEmpty("tasks[0].actions[0]"),
                isEmpty("tasks[0].actions[1]"));
        assertFieldValid(descriptor, "tasks[0].actions[2]");
    }


    @Test
    public void canValidateEnvironmentVariables() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertValidationErrors(descriptor, isNull("environmentVariables"));

        descriptor.environmentVariables = new ArrayList<>();
        assertFieldValid(descriptor, "environmentVariables");

        JsonComponentDescriptor.EnvironmentVariable environmentVariable = new JsonComponentDescriptor.EnvironmentVariable();
        descriptor.environmentVariables.add(environmentVariable);

        assertFieldValid(descriptor, "environmentVariables[0].sep");
        assertValidationErrors(descriptor,
                isEmpty("environmentVariables[0].name"),
                isNull("environmentVariables[0].value"));

        environmentVariable.sep = ":hello:";
        assertValidationErrors(descriptor, "environmentVariables[0].sep must be \":\" or null");

        environmentVariable.sep = ":";
        assertFieldValid(descriptor, "environmentVariables[0].sep");
    }


    @Test
    public void testCanValidateAlgorithm() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertValidationErrors(descriptor, isNull("algorithm"));

        descriptor.algorithm = new JsonComponentDescriptor.Algorithm();
        assertValidationErrors(descriptor,
                isNull("algorithm.actionType"),
                isNull("algorithm.requiresCollection"),
                doesNotSupportBatchOrStream());
        
        descriptor.algorithm.actionType = ActionType.DETECTION;
        assertFieldValid(descriptor, "algorithm.actionType");

        JsonComponentDescriptor.AlgoRequires algoRequires = new JsonComponentDescriptor.AlgoRequires();
        descriptor.algorithm.requiresCollection = algoRequires;
        assertFieldValid(descriptor, "algorithm.requiresCollection");
        assertValidationErrors(descriptor, isNull("algorithm.requiresCollection.states"));

        algoRequires.states = new ArrayList<>();
        assertFieldValid(descriptor, "algorithm.requiresCollection.states");

        algoRequires.states.add(null);
        algoRequires.states.add("asdf");
        assertFieldValid(descriptor, "algorithm.requiresCollection.states[1]");
        assertValidationErrors(descriptor, isEmpty("algorithm.requiresCollection.states[0]"));
    }

    @Test
    public void canValidateAlgoProvidesCollection() {
        String algoProvidesPath = "algorithm.providesCollection";
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        descriptor.algorithm = new JsonComponentDescriptor.Algorithm();

        assertValidationErrors(descriptor, isNull(algoProvidesPath));

        descriptor.algorithm.providesCollection = new JsonComponentDescriptor.AlgoProvides();
        assertFieldValid(descriptor, algoProvidesPath);
        assertValidationErrors(descriptor,
                isNull(algoProvidesPath + ".states"),
                isNull(algoProvidesPath + ".properties"));

        descriptor.algorithm.providesCollection.properties = new ArrayList<>();
        assertFieldValid(descriptor, algoProvidesPath + ".properties");

        String propertyPath = algoProvidesPath + ".properties[0]";
        JsonComponentDescriptor.AlgoProvidesProp property = new JsonComponentDescriptor.AlgoProvidesProp();
        property.name = "";
        property.defaultValue = "";
        property.propertiesKey = null;

        descriptor.algorithm.providesCollection.properties.add(property);

        assertFieldValid(descriptor, propertyPath + ".defaultValue");
        assertFieldValid(descriptor, propertyPath + ".propertiesKey");
        assertValidationErrors(descriptor,
                isEmpty(propertyPath + ".description"),
                isEmpty(propertyPath + ".name"),
                isNull(propertyPath + ".type"));


        property.defaultValue = null;
        property.propertiesKey = "\t\n      ";
        assertFieldValid(descriptor, propertyPath + ".defaultValue");
        assertValidationErrors(descriptor,
                isEmpty(propertyPath + ".propertiesKey"));

        property.defaultValue = null;
        property.propertiesKey = "asdf";
        assertFieldValid(descriptor, propertyPath + ".defaultValue");
        assertFieldValid(descriptor, propertyPath + ".propertiesKey");
        assertValidationErrors(descriptor,
                isEmpty(propertyPath + ".description"),
                isEmpty(propertyPath + ".name"),
                isNull(propertyPath + ".type"));

        property.defaultValue = "";
        property.propertiesKey = "";
        assertValidationErrors(descriptor,
                hasInvalidProvidesProp(propertyPath + ".defaultValue"),
                hasInvalidProvidesProp(propertyPath + ".propertiesKey"));


        property.defaultValue = null;
        property.propertiesKey = null;
        assertValidationErrors(descriptor,
                hasInvalidProvidesProp(propertyPath + ".defaultValue"),
                hasInvalidProvidesProp(propertyPath + ".propertiesKey"));
    }


    @Test
    public void canValidateAction() {
        JsonComponentDescriptor descriptor = new JsonComponentDescriptor();
        assertFieldValid(descriptor, "actions");

        descriptor.actions = new ArrayList<>();
        assertFieldValid(descriptor, "actions");

        JsonComponentDescriptor.Action action = new JsonComponentDescriptor.Action();
        action.algorithm = "   ";
        descriptor.actions.add(action);
        assertValidationErrors(descriptor,
                isEmpty("actions[0].name"),
                isEmpty("actions[0].algorithm"),
                isNull("actions[0].properties"));

        action.properties = new ArrayList<>();
        assertFieldValid(descriptor, "actions[0].properties");

        JsonComponentDescriptor.ActionProperty actionProp = new JsonComponentDescriptor.ActionProperty();
        actionProp.name = " ";
        action.properties.add(actionProp);
        assertValidationErrors(descriptor,
                isEmpty("actions[0].properties[0].name"),
                isNull("actions[0].properties[0].value"));

        actionProp.name = "asdf";
        actionProp.value = "";
        assertFieldValid(descriptor, "actions[0].properties[0].name");
        assertFieldValid(descriptor, "actions[0].properties[0].value");
    }


    private void assertValidationErrors(JsonComponentDescriptor descriptor, String... messages) {
        assertTrue("No messages passed in to check", messages.length > 0);
        try {
            _validator.validate(descriptor);
            fail();
        }
        catch (InvalidComponentDescriptorException ex) {
            String errorMessage = ex.getMessage();
            boolean containsAllMsgs = Stream.of(messages)
                    .allMatch(errorMessage::contains);
            assertTrue("Exception message did not contain expected message(s)", containsAllMsgs);
        }
    }

    private void assertNoValidationErrors(JsonComponentDescriptor descriptor, String... messages) {
        assertTrue("No messages passed in to check", messages.length > 0);
        try {
            _validator.validate(descriptor);
        }
        catch (InvalidComponentDescriptorException ex) {
            String errorMessage = ex.getMessage();
            boolean containsAnyMsgs = Stream.of(messages)
                    .anyMatch(errorMessage::contains);
            assertFalse("Exception message contained unexpected message(s)", containsAnyMsgs);
        }
    }

    private void assertFieldValid(JsonComponentDescriptor descriptor, String field) {
        try {
            _validator.validate(descriptor);
        }
        catch (InvalidComponentDescriptorException ex) {
            boolean fieldHasViolation = ex.getValidationErrors()
                    .stream()
                    .anyMatch(cv -> fieldHasViolation(cv, field));
            assertFalse(fieldHasViolation);
        }
    }

    private static boolean fieldHasViolation(ConstraintViolation cv, String field) {
        return cv.getPropertyPath()
                .toString()
                .equalsIgnoreCase(field);
    }

    private static String isEmpty(String field) {
       return field + " may not be empty";
    }

    private static String isNull(String field) {
        return field + " may not be null" ;
    }

    private static String hasInvalidProvidesProp(String field) {
        return field + " must provide either a defaultValue or propertiesKey, but not both.";
    }


    private static String doesNotSupportBatchOrStream() {
    	return "must contain batchLibrary, streamLibrary, or both";
    }
}
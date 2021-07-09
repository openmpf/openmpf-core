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

package org.mitre.mpf.wfm.util;

import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.ActionType;
import org.mitre.mpf.rest.api.pipelines.Task;
import org.mitre.mpf.wfm.data.entities.persistent.*;
import org.mitre.mpf.wfm.enums.MediaType;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.mitre.mpf.wfm.service.WorkflowPropertyService;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;


@Component
public class AggregateJobPropertiesUtil {

    private final PropertiesUtil _propertiesUtil;

    private final WorkflowPropertyService _workflowPropertyService;

    @Inject
    public AggregateJobPropertiesUtil(
            PropertiesUtil propertiesUtil,
            WorkflowPropertyService workflowPropertyService) {
        _propertiesUtil = propertiesUtil;
        _workflowPropertyService = workflowPropertyService;
    }



    // in order of precedence
    private enum PropertyLevel {
        NONE, SYSTEM, WORKFLOW, ALGORITHM, ACTION, JOB, OVERRIDDEN_ALGORITHM, MEDIA,
        ENVIRONMENT_VARIABLE }


    private static class PropertyInfo {
        private final String _name;
        public String getName() {
            return _name;
        }

        private final String _value;
        public final String getValue() {
            return _value;
        }

        private final PropertyLevel _level;
        public PropertyLevel getLevel() {
            return _level;
        }

        public double getNumericValue() {
            return Double.parseDouble(_value);
        }

        public boolean isLessThanOrEqualTo(double compare) {
            return getNumericValue() <= compare;
        }

        public PropertyInfo(String name, String value, PropertyLevel level) {
            _name = name;
            _value = value;
            _level = level;
        }

        public static PropertyInfo missing(String propertyName) {
            return new PropertyInfo(propertyName, null, PropertyLevel.NONE);
        }
    }

    /**
     * Return the value of the named property, checking for that property in each of the categories of property
     * collections, using the priority scheme (highest priority to lowest priority):
     * media > overridden algorithm > job > action > default algorithm > workflow
     *
     * @param propertyName property name to check for
     * @param action Action currently being processed
     * @param mediaSpecificProperties Media specific properties for media currently being processed
     * @param mediaType Type of media currently being processed
     * @param pipeline Pipeline currently being processed
     * @param overriddenAlgorithmProperties Overridden algorithm properties for the job  currently being processed
     * @param jobProperties Job properties for job currently being processed
     * @param systemPropertiesSnapshot System properties snapshot for job currently being processed
     * @return property info after checking for that property within the prioritized categories of property containers
     */
    private PropertyInfo getPropertyInfo(
            String propertyName,
            Map<String, String> mediaSpecificProperties,
            MediaType mediaType,
            Action action,
            JobPipelineElements pipeline,
            Map<String, ? extends Map<String, String>> overriddenAlgorithmProperties,
            Map<String, String> jobProperties,
            SystemPropertiesSnapshot systemPropertiesSnapshot) {

        var envPropVal = System.getenv("MPF_PROP_" + propertyName);
        if (envPropVal != null) {
            return new PropertyInfo(propertyName, envPropVal, PropertyLevel.ENVIRONMENT_VARIABLE);
        }

        var mediaPropVal = mediaSpecificProperties.get(propertyName);
        if (mediaPropVal != null) {
            return new PropertyInfo(propertyName, mediaPropVal, PropertyLevel.MEDIA);
        }

        if (action != null) {
            Map<String, String> algoProperties = overriddenAlgorithmProperties.get(action.getAlgorithm());
            if (algoProperties != null) {
                var propVal = algoProperties.get(propertyName);
                if (propVal != null) {
                    return new PropertyInfo(propertyName, propVal, PropertyLevel.OVERRIDDEN_ALGORITHM);
                }
            }
        }

        var jobPropVal = jobProperties.get(propertyName);
        if (jobPropVal != null) {
            return new PropertyInfo(propertyName, jobPropVal, PropertyLevel.JOB);
        }

        if (action != null) {
            var actionPropVal = action.getPropertyValue(propertyName);
            if (actionPropVal != null) {
                return new PropertyInfo(propertyName, actionPropVal, PropertyLevel.ACTION);
            }

            var algorithm = pipeline.getAlgorithm(action.getAlgorithm());

            var algoProperty = algorithm.getProperty(propertyName);
            if (algoProperty != null) {
                if (algoProperty.getDefaultValue() != null) {
                    return new PropertyInfo(propertyName, algoProperty.getDefaultValue(), PropertyLevel.ALGORITHM);
                }

                if (systemPropertiesSnapshot != null) {
                    var snapshotValue = systemPropertiesSnapshot.lookup(algoProperty.getPropertiesKey());
                    if (snapshotValue != null) {
                        return new PropertyInfo(propertyName, snapshotValue, PropertyLevel.ALGORITHM);
                    }
                }

                var propertiesUtilValue = _propertiesUtil.lookup(algoProperty.getPropertiesKey());
                if (propertiesUtilValue != null) {
                    return new PropertyInfo(propertyName, propertiesUtilValue, PropertyLevel.ALGORITHM);
                }
            }
        }

        if (mediaType != null) {
            var workflowPropVal =  _workflowPropertyService.getPropertyValue(propertyName, mediaType,
                                                                             systemPropertiesSnapshot);
            if (workflowPropVal != null) {
                return new PropertyInfo(propertyName, workflowPropVal, PropertyLevel.WORKFLOW);
            }
        }
        return PropertyInfo.missing(propertyName);
    }



    public Map<String, String> getPropertyMap(BatchJob job, Media media, Action action) {
        return getPropertyMap(
                action,
                media.getMediaSpecificProperties(),
                media.getType(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getPipelineElements(),
                job.getSystemPropertiesSnapshot());
    }


    public Map<String, String> getPropertyMap(StreamingJob job, Action action) {
        return getPropertyMap(
                action,
                job.getStream().getMediaProperties(),
                MediaType.VIDEO,
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getPipelineElements(),
                null);
    }

    private Map<String, String> getPropertyMap(
            Action action,
            Map<String, String> mediaProperties,
            MediaType mediaType,
            Map<String, ? extends Map<String, String>> allOverriddenAlgorithmProperties,
            Map<String, String> jobProperties,
            JobPipelineElements pipelineElements,
            SystemPropertiesSnapshot systemPropertiesSnapshot) {

        var allKeys = new HashSet<>(mediaProperties.keySet());

        Map<String, String> overriddenAlgoProps = allOverriddenAlgorithmProperties.get(action.getAlgorithm());
        if (overriddenAlgoProps != null) {
            allKeys.addAll(overriddenAlgoProps.keySet());
        }

        allKeys.addAll(jobProperties.keySet());

        action.getProperties().forEach(p -> allKeys.add(p.getName()));

        pipelineElements.getAlgorithm(action.getAlgorithm())
                .getProvidesCollection()
                .getProperties()
                .forEach(p -> allKeys.add(p.getName()));

        _workflowPropertyService.getProperties(mediaType)
                .forEach(p -> allKeys.add(p.getName()));

        return allKeys.stream()
                .map(pn -> getPropertyInfo(pn, mediaProperties, mediaType, action, pipelineElements,
                                           allOverriddenAlgorithmProperties, jobProperties, systemPropertiesSnapshot))
                .filter(pn -> pn.getLevel() != PropertyLevel.NONE)
                .collect(toMap(PropertyInfo::getName, PropertyInfo::getValue));
    }


    public String getValue(String propertyName, BatchJob job, Media media,
                           Action action) {
        return getPropertyInfo(
                propertyName,
                media.getMediaSpecificProperties(),
                media.getType(),
                action,
                job.getPipelineElements(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getSystemPropertiesSnapshot()
        ).getValue();
    }


    public String getValue(String propertyName, JobPart jobPart) {
        return getValue(propertyName, jobPart.getJob(), jobPart.getMedia(), jobPart.getAction());
    }

    public String getValue(String propertyName, BatchJob job, Media media) {
        return getValue(propertyName, job, media, null);
    }


    public Function<String, String> getCombinedProperties(
            Action action,
            JobPipelineElements pipeline,
            Media media,
            Map<String, String> jobProperties,
            Map<String, ? extends Map<String, String>> overriddenAlgoProps,
            SystemPropertiesSnapshot propertiesSnapshot) {
        return propertyName -> getPropertyInfo(
                propertyName,
                media.getMediaSpecificProperties(),
                media.getType(),
                action,
                pipeline,
                overriddenAlgoProps,
                jobProperties,
                propertiesSnapshot
        ).getValue();
    }


    public Function<String, String> getCombinedProperties(BatchJob job, Media media,
                                                          Action action) {
        return propName -> getValue(propName, job, media, action);
    }



    public Function<String, String> getCombinedProperties(BatchJob job, Media media) {
        return propName -> getPropertyInfo(
                propName,
                media.getMediaSpecificProperties(),
                media.getType(),
                null,
                job.getPipelineElements(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getSystemPropertiesSnapshot()
        ).getValue();
    }



    public Function<String, String> getCombinedProperties(BatchJob job, URI mediaUri) {
        Media matchingMedia = null;
        for (var media : job.getMedia()) {
            try {
                if (mediaUri.equals(new URI(media.getUri()))) {
                    matchingMedia = media;
                    break;
                }
            }
            catch (URISyntaxException ignored) {
                // Continue searching for matching media since a job could have a combination of good and bad media.
            }
        }

        Map<String, String> mediaProperties = matchingMedia == null
                ? Map.of()
                : matchingMedia.getMediaSpecificProperties();

        MediaType mediaType = matchingMedia == null
                ? null
                : matchingMedia.getType();

        return propName -> getPropertyInfo(
                propName,
                mediaProperties,
                mediaType,
                null,
                job.getPipelineElements(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getSystemPropertiesSnapshot()
        ).getValue();
    }




    public String calculateFrameInterval(Action action, BatchJob job, Media media, int systemFrameInterval,
                                         int systemFrameRateCap, double mediaFPS) {

        PropertyInfo frameIntervalPropInfo = getPropertyInfo(
                MpfConstants.MEDIA_SAMPLING_INTERVAL_PROPERTY,
                media.getMediaSpecificProperties(),
                media.getType(),
                action,
                job.getPipelineElements(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getSystemPropertiesSnapshot());

        PropertyInfo frameRateCapPropInfo = getPropertyInfo(
                MpfConstants.FRAME_RATE_CAP_PROPERTY,
                media.getMediaSpecificProperties(),
                media.getType(),
                action,
                job.getPipelineElements(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getSystemPropertiesSnapshot());

        if (frameIntervalPropInfo.getLevel() == PropertyLevel.NONE) {
            frameIntervalPropInfo = new PropertyInfo(MpfConstants.MEDIA_SAMPLING_INTERVAL_PROPERTY,
                                                     Integer.toString(systemFrameInterval), PropertyLevel.SYSTEM);
        }

        if (frameRateCapPropInfo.getLevel() == PropertyLevel.NONE) {
            frameRateCapPropInfo = new PropertyInfo(MpfConstants.FRAME_RATE_CAP_PROPERTY,
                                                    Integer.toString(systemFrameRateCap), PropertyLevel.SYSTEM);
        }

        PropertyInfo propInfoToUse;
        if (frameRateCapPropInfo.getLevel().ordinal() >= frameIntervalPropInfo.getLevel().ordinal()) {
            propInfoToUse = frameRateCapPropInfo; // prefer frame rate cap
        } else {
            propInfoToUse = frameIntervalPropInfo;
        }

        if (propInfoToUse.isLessThanOrEqualTo(0)) {
            if (propInfoToUse.getName().equals(MpfConstants.MEDIA_SAMPLING_INTERVAL_PROPERTY)) {
                propInfoToUse = frameRateCapPropInfo;
            } else {
                propInfoToUse = frameIntervalPropInfo;
            }
        }

        if (propInfoToUse.isLessThanOrEqualTo(0)) {
            return "1"; // frame interval and frame rate cap are both disabled
        }

        if (propInfoToUse.getName().equals(MpfConstants.MEDIA_SAMPLING_INTERVAL_PROPERTY)) {
            return propInfoToUse.getValue();
        }

        int calcFrameInterval = (int) Math.max(1, Math.floor(mediaFPS / frameRateCapPropInfo.getNumericValue()));
        return Integer.toString(calcFrameInterval);
    }



    // Get collection of tasks that need to be merged with the tasks that come immediately before them.
    public Set<Integer> getTasksToMerge(Media media, BatchJob job) {
        var tasksToMerge = new HashSet<Integer>();
        for (int taskIndex = 1; taskIndex < job.getPipelineElements().getTaskCount(); taskIndex++) {
            Task task = job.getPipelineElements().getTask(taskIndex);

            for (int actionIndex = 0; actionIndex < task.getActions().size(); actionIndex++) {
                Action action = job.getPipelineElements().getAction(taskIndex, actionIndex);
                ActionType actionType = job.getPipelineElements().getAlgorithm(taskIndex, actionIndex).getActionType();

                boolean shouldMergeWithPreviousTask = Boolean.parseBoolean(
                        getValue(MpfConstants.OUTPUT_MERGE_WITH_PREVIOUS_TASK_PROPERTY, job, media, action));

                if (actionType == ActionType.DETECTION && shouldMergeWithPreviousTask) {
                    tasksToMerge.add(taskIndex);
                }
            }
        }

        return tasksToMerge;
    }


    public boolean isOutputLastTaskOnly(Media media, BatchJob job) {
        // Action properties and algorithm properties are not checked because it doesn't make sense
        // to apply OUTPUT_LAST_TASK_ONLY to a single task.
        return Boolean.parseBoolean(
                getValue(MpfConstants.OUTPUT_LAST_TASK_ONLY_PROPERTY, job, media));
    }
}

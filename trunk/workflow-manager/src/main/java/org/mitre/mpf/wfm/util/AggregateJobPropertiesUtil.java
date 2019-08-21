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

package org.mitre.mpf.wfm.util;

import com.google.common.collect.ImmutableSet;
import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.wfm.data.entities.persistent.*;
import org.mitre.mpf.wfm.enums.MediaType;
import org.mitre.mpf.wfm.enums.MpfConstants;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;


@Component
public class AggregateJobPropertiesUtil {

    public static final ImmutableSet<String> TRANSFORM_PROPERTIES = ImmutableSet.of(
            MpfConstants.ROTATION_PROPERTY,
            MpfConstants.HORIZONTAL_FLIP_PROPERTY,
            MpfConstants.SEARCH_REGION_TOP_LEFT_X_DETECTION_PROPERTY,
            MpfConstants.SEARCH_REGION_TOP_LEFT_Y_DETECTION_PROPERTY,
            MpfConstants.SEARCH_REGION_BOTTOM_RIGHT_X_DETECTION_PROPERTY,
            MpfConstants.SEARCH_REGION_BOTTOM_RIGHT_Y_DETECTION_PROPERTY,
            MpfConstants.SEARCH_REGION_ENABLE_DETECTION_PROPERTY,
            MpfConstants.AUTO_ROTATE_PROPERTY,
            MpfConstants.AUTO_FLIP_PROPERTY);


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
    private enum PropertyLevel { NONE, SYSTEM, WORKFLOW, ALGORITHM, ACTION, JOB, OVERRIDDEN_ALGORITHM, MEDIA }


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
            JobPipelineComponents pipeline,
            Map<String, ? extends Map<String, String>> overriddenAlgorithmProperties,
            Map<String, String> jobProperties,
            SystemPropertiesSnapshot systemPropertiesSnapshot) {

        var mediaPropVal = mediaSpecificProperties.get(propertyName);
        if (mediaPropVal != null) {
            return new PropertyInfo(propertyName, mediaPropVal, PropertyLevel.MEDIA);
        }

        //TODO: Add comment explaining if we decide to keep this behavior
        boolean isTransformProperty = TRANSFORM_PROPERTIES.contains(propertyName);
        if (isTransformProperty && containsTransformProperty(mediaSpecificProperties)) {
            return PropertyInfo.missing(propertyName);
        }

        if (action != null) {
            Map<String, String> algoProperties = overriddenAlgorithmProperties.get(action.getAlgorithm());
            if (algoProperties != null) {
                var propVal = algoProperties.get(propertyName);
                if (propVal != null) {
                    return new PropertyInfo(propertyName, propVal, PropertyLevel.OVERRIDDEN_ALGORITHM);
                }

                if (isTransformProperty && containsTransformProperty(algoProperties)) {
                    return PropertyInfo.missing(propertyName);
                }
            }
        }

        var jobPropVal = jobProperties.get(propertyName);
        if (jobPropVal != null) {
            return new PropertyInfo(propertyName, jobPropVal, PropertyLevel.JOB);
        }

        if (isTransformProperty && containsTransformProperty(jobProperties)) {
            return PropertyInfo.missing(propertyName);
        }

        if (action != null) {
            var actionPropVal = action.getPropertyValue(propertyName);
            if (actionPropVal != null) {
                return new PropertyInfo(propertyName, actionPropVal, PropertyLevel.ACTION);
            }
            if (isTransformProperty && containsTransformProperty(action::getPropertyValue)) {
                return PropertyInfo.missing(propertyName);
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
            if (isTransformProperty && containsTransformProperty(algorithm::getProperty)) {
                return PropertyInfo.missing(propertyName);
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


    public String getValue(String propertyName, BatchJob job, Media media,
                           Action action) {
        return getPropertyInfo(
                propertyName,
                media.getMediaSpecificProperties(),
                media.getMediaType(),
                action,
                job.getPipelineComponents(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getSystemPropertiesSnapshot()
        ).getValue();
    }


    public Map<String, String> getPropertyMap(StreamingJob job, Action action) {
        var allKeys = new HashSet<String>();

        job.getPipelineComponents().getAlgorithm(action.getAlgorithm())
                .getProvidesCollection()
                .getProperties()
                .forEach(p -> allKeys.add(p.getName()));

        action.getProperties()
                .forEach(p -> allKeys.add(p.getName()));

        allKeys.addAll(job.getJobProperties().keySet());

        Map<String, String> overriddenAlgoProps = job.getOverriddenAlgorithmProperties().get(action.getAlgorithm());
        if (overriddenAlgoProps != null) {
            allKeys.addAll(overriddenAlgoProps.keySet());
        }

        allKeys.addAll(job.getStream().getMediaProperties().keySet());

        return allKeys.stream()
                .map(pn -> getPropertyInfo(pn, job.getStream().getMediaProperties(), MediaType.VIDEO,
                                           action, job.getPipelineComponents(), job.getOverriddenAlgorithmProperties(),
                                           job.getJobProperties(), null))
                .filter(pn -> pn.getLevel() != PropertyLevel.NONE)
                .collect(toMap(PropertyInfo::getName, PropertyInfo::getValue));
    }


    public Map<String, String> getPropertyMap(BatchJob job, Media media, Action action) {

        var allKeys = new HashSet<>(media.getMediaSpecificProperties().keySet());

        Map<String, String> overriddenAlgoProps = job.getOverriddenAlgorithmProperties().get(action.getAlgorithm());
        if (overriddenAlgoProps != null) {
            allKeys.addAll(overriddenAlgoProps.keySet());
        }

        allKeys.addAll(job.getJobProperties().keySet());

        action.getProperties().forEach(p -> allKeys.add(p.getName()));

        job.getPipelineComponents()
                .getAlgorithm(action.getAlgorithm())
                .getProvidesCollection()
                .getProperties()
                .forEach(p -> allKeys.add(p.getName()));

        _workflowPropertyService.getProperties(media.getMediaType())
                .forEach(p -> allKeys.add(p.getName()));


        return allKeys.stream()
                .map(pn -> getPropertyInfo(
                        pn, media.getMediaSpecificProperties(), media.getMediaType(), action,
                        job.getPipelineComponents(), job.getOverriddenAlgorithmProperties(), job.getJobProperties(),
                        job.getSystemPropertiesSnapshot()))
                .filter(pn -> pn.getLevel() != PropertyLevel.NONE)
                .collect(toMap(PropertyInfo::getName, PropertyInfo::getValue));
    }



    private static boolean containsTransformProperty(Map<String, String> items) {
        return containsTransformProperty(items::get);
    }


    private static boolean containsTransformProperty(Function<String, ?> propertyLookupFn) {
        return TRANSFORM_PROPERTIES.stream()
                .anyMatch(tp -> propertyLookupFn.apply(tp) != null);
    }


    public Function<String, String> getCombinedProperties(
            Action action,
            JobPipelineComponents pipeline,
            Media media,
            Map<String, String> jobProperties,
            Map<String, ? extends Map<String, String>> overriddenAlgoProps,
            SystemPropertiesSnapshot propertiesSnapshot) {
        return propertyName -> getPropertyInfo(
                propertyName,
                media.getMediaSpecificProperties(),
                media.getMediaType(),
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
                media.getMediaType(),
                null,
                job.getPipelineComponents(),
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
                : matchingMedia.getMediaType();

        return propName -> getPropertyInfo(
                propName,
                mediaProperties,
                mediaType,
                null,
                job.getPipelineComponents(),
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
                media.getMediaType(),
                action,
                job.getPipelineComponents(),
                job.getOverriddenAlgorithmProperties(),
                job.getJobProperties(),
                job.getSystemPropertiesSnapshot());

        PropertyInfo frameRateCapPropInfo = getPropertyInfo(
                MpfConstants.FRAME_RATE_CAP_PROPERTY,
                media.getMediaSpecificProperties(),
                media.getMediaType(),
                action,
                job.getPipelineComponents(),
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
}

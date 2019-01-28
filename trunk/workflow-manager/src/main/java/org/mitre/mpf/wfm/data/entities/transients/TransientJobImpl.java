/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2018 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2018 The MITRE Corporation                                       *
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


package org.mitre.mpf.wfm.data.entities.transients;

import com.google.common.collect.*;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;

import java.util.*;

public class TransientJobImpl implements TransientJob {

    private final long id;
    @Override
    public long getId() { return id; }


    private BatchJobStatusType status = BatchJobStatusType.INITIALIZED;
    @Override
    public BatchJobStatusType getStatus() { return status; }
    public void setStatus(BatchJobStatusType status) { this.status = status; }


    private final TransientPipeline pipeline;
    @Override
    public TransientPipeline getPipeline() { return pipeline; }


    private int currentStage;
    @Override
    public int getCurrentStage() { return currentStage; }
    public void setCurrentStage(int currentStage) { this.currentStage = currentStage; }


    private final String externalId;
    @Override
    public String getExternalId() { return externalId; }


    private final int priority;
    @Override
    public int getPriority() { return  priority; }


    private final boolean outputEnabled;
    @Override
    public boolean isOutputEnabled() { return outputEnabled; }


    private final ImmutableSortedMap<Long, TransientMedia> media;
    @Override
    public ImmutableCollection<TransientMedia> getMedia() { return media.values(); }
    @Override
    public TransientMedia getMedia(long mediaId) {
        return media.get(mediaId);
    }


    private final ImmutableTable<String, String, String> overriddenAlgorithmProperties;
    @Override
    public ImmutableTable<String, String, String> getOverriddenAlgorithmProperties() { return overriddenAlgorithmProperties; }


    private final ImmutableMap<String, String> overriddenJobProperties;
    @Override
    public ImmutableMap<String, String> getOverriddenJobProperties() { return overriddenJobProperties; }


    private boolean cancelled;
    @Override
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean isCancelled) { this.cancelled = isCancelled; }


    private final String callbackURL;
    @Override
    public String getCallbackURL() { return callbackURL; }


    private final String callbackMethod;
    @Override
    public String getCallbackMethod() { return callbackMethod; }


    private final Set<String> errors = new HashSet<>();
    @Override
    public Set<String> getErrors() {
        return Collections.unmodifiableSet(errors);
    }
    public void addError(String errorMsg) {
        errors.add(errorMsg);
    }


    private final Set<String> warnings = new HashSet<>();
    @Override
    public Set<String> getWarnings() {
        return Collections.unmodifiableSet(warnings);
    }
    public void addWarning(String warningMsg) {
        warnings.add(warningMsg);
    }


    // Detection system properties for this job should be immutable, the detection system property values
    // shouldn't change once the job is created even if they are changed on the UI by an admin..
    // The detectionSystemPropertiesSnapshot contains the values of the detection system properties at the time this batch job was created.
    private final SystemPropertiesSnapshot detectionSystemPropertiesSnapshot;
    @Override
    public SystemPropertiesSnapshot getSystemPropertiesSnapshot() { return detectionSystemPropertiesSnapshot; }


    private final List<DetectionProcessingError> detectionProcessingErrors = new ArrayList<>();
    @Override
    public List<DetectionProcessingError> getDetectionProcessingErrors() {
        return Collections.unmodifiableList(detectionProcessingErrors);
    }
    public void addDetectionProcessingError(DetectionProcessingError error) {
        detectionProcessingErrors.add(error);
    }


    public TransientJobImpl(
            long id,
            String externalId,
            SystemPropertiesSnapshot detectionSystemPropertiesSnapshot,
            TransientPipeline pipeline,
            int priority,
            boolean outputEnabled,
            String callbackURL,
            String callbackMethod,
            List<TransientMedia> media,
            Map<String, String> jobProperties,
            Map<String, Map<String, String>> algorithmProperties) {
        this.id = id;
        this.externalId = externalId;
        this.detectionSystemPropertiesSnapshot = detectionSystemPropertiesSnapshot;
        this.pipeline = pipeline;
        this.priority = priority;
        this.outputEnabled = outputEnabled;
        this.callbackURL = callbackURL;
        this.callbackMethod = callbackMethod;

        this.media = ImmutableSortedMap.copyOf(Maps.uniqueIndex(media, TransientMedia::getId));

        this.overriddenJobProperties = ImmutableMap.copyOf(jobProperties);

        ImmutableTable.Builder<String, String, String> tableBuilder = ImmutableTable.builder();
        for (Map.Entry<String, Map<String, String>> algoEntry : algorithmProperties.entrySet()) {
            for (Map.Entry<String, String> algoProp : algoEntry.getValue().entrySet()) {
                tableBuilder.put(algoEntry.getKey(), algoProp.getKey(), algoProp.getValue());
            }
        }
        this.overriddenAlgorithmProperties = tableBuilder.build();
    }
}

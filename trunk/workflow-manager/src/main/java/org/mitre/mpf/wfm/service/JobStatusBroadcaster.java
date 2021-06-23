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


package org.mitre.mpf.wfm.service;

import org.mitre.mpf.mvc.controller.AtmosphereController;
import org.mitre.mpf.mvc.model.JobStatusMessage;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.StreamingJobStatusType;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.time.Instant;

@Service
public class JobStatusBroadcaster {

    private final PropertiesUtil _propertiesUtil;

    private final JobProgress _jobProgress;

    @Inject
    JobStatusBroadcaster(PropertiesUtil propertiesUtil, JobProgress jobProgress) {
        _propertiesUtil = propertiesUtil;
        _jobProgress = jobProgress;
    }

    public void broadcast(long jobId, BatchJobStatusType jobStatus) {
        var progress = _jobProgress.getJobProgress(jobId)
                .orElseGet(() -> jobStatus.isTerminal() ? 100.0f : 0.0f);
        broadcast(jobId, progress, jobStatus, null);
    }

    public void broadcast(long jobId, double progress, BatchJobStatusType jobStatus) {
        broadcast(jobId, progress, jobStatus, null);
    }

    public void broadcast(long jobId, double progress, BatchJobStatusType jobStatus, Instant endDate) {
        if (_propertiesUtil.isBroadcastJobStatusEnabled()) {
            AtmosphereController.broadcast(new JobStatusMessage(jobId, progress, jobStatus, endDate));
        }
    }


    public void broadcast(long jobId, double progress, StreamingJobStatusType jobStatus) {
        broadcast(jobId, progress, jobStatus, null);
    }

    public void broadcast(long jobId, double progress, StreamingJobStatusType jobStatus, Instant endDate) {
        if (_propertiesUtil.isBroadcastJobStatusEnabled()) {
            AtmosphereController.broadcast(new JobStatusMessage(jobId, progress, jobStatus, endDate));
        }
    }
}

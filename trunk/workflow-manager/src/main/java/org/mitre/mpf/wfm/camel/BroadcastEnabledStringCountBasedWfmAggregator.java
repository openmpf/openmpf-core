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

package org.mitre.mpf.wfm.camel;

import org.apache.camel.Exchange;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.service.JobStatusBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component(BroadcastEnabledStringCountBasedWfmAggregator.REF)
public class BroadcastEnabledStringCountBasedWfmAggregator extends StringCountBasedWfmAggregator {
    private static final Logger log = LoggerFactory.getLogger(BroadcastEnabledStringCountBasedWfmAggregator.class);
    public static final String REF = "broadcastEnabledStringCountBasedWfmAggregator";

    @Autowired
    private InProgressBatchJobsService inProgressBatchJobs;

    @Autowired
    private JobProgress jobProgressStore;

    @Autowired
    private JobStatusBroadcaster jobStatusBroadcaster;

    @Override
    public void onResponse(Exchange newExchange) {
        super.onResponse(newExchange);
        Object suppressBroadcast = newExchange.getIn().getHeader(MpfHeaders.SUPPRESS_BROADCAST);
        if (suppressBroadcast instanceof Boolean && (boolean) suppressBroadcast) {
            return;
        }

        try {
            int aggregateCount = newExchange.getOut().getHeader(MpfHeaders.AGGREGATED_COUNT, Integer.class);
            int splitSize = newExchange.getOut().getHeader(MpfHeaders.SPLIT_SIZE, Integer.class);
            long jobId = newExchange.getOut().getHeader(MpfHeaders.JOB_ID, Long.class);

            BatchJob job = inProgressBatchJobs.getJob(jobId);
            int tasksCompleted = job.getCurrentTaskIndex();
            int totalTasks = job.getPipelineElements().getTaskCount();
            float progressInCurrentTask = (float) aggregateCount / splitSize;
            float jobProgress = (tasksCompleted + progressInCurrentTask) / totalTasks * 100;

            jobStatusBroadcaster.broadcast(jobId, jobProgress, job.getStatus());
            jobProgressStore.setJobProgress(jobId, jobProgress);
        } catch (Exception e) {
            log.error("Error getting necessary information to create a job progress update.");
        }
    }
}

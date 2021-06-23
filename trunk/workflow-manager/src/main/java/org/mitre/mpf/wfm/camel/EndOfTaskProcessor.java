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
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.service.JobStatusBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component(EndOfTaskProcessor.REF)
public class EndOfTaskProcessor extends WfmProcessor {
    public static final String REF = "endOfTaskProcessor";
    private static final Logger log = LoggerFactory.getLogger(EndOfTaskProcessor.class);

    @Autowired
    private InProgressBatchJobsService inProgressBatchJobs;

    @Autowired
    private JobProgress jobProgressStore;

    @Autowired
    private JobStatusBroadcaster jobStatusBroadcaster;

    @Override
    public void wfmProcess(Exchange exchange) throws WfmProcessingException {
        long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
        inProgressBatchJobs.incrementTask(jobId);
        BatchJob job = inProgressBatchJobs.getJob(jobId);

        log.info("[Job {}|{}|*] Task Complete! Progress is now {}/{}.",
                 jobId,
                 job.getCurrentTaskIndex() - 1,
                 job.getCurrentTaskIndex(),
                 job.getPipelineElements().getTaskCount());


        if (job.getCurrentTaskIndex() >= job.getPipelineElements().getTaskCount()) {
            jobProgressStore.setJobProgress(jobId, 99.0f);
            jobStatusBroadcaster.broadcast(jobId, job.getStatus());
            log.debug("[Job {}|*|*] All tasks have completed. Setting the {} flag.", jobId, MpfHeaders.JOB_COMPLETE);
            exchange.getOut().setHeader(MpfHeaders.JOB_COMPLETE, Boolean.TRUE);
        }

        exchange.getOut().setHeader(MpfHeaders.JMS_PRIORITY, job.getPriority());
    }
}

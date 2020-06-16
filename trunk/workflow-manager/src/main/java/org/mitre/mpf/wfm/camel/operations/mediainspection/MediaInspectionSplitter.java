/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2020 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2020 The MITRE Corporation                                       *
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

package org.mitre.mpf.wfm.camel.operations.mediainspection;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.impl.DefaultMessage;
import org.mitre.mpf.wfm.camel.WfmSplitter;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component(MediaInspectionSplitter.REF)
public class MediaInspectionSplitter extends WfmSplitter {
    private static final Logger log = LoggerFactory.getLogger(MediaInspectionSplitter.class);
    public static final String REF = "mediaInspectionSplitter";

    @Autowired
    private InProgressBatchJobsService inProgressJobs;

    @Override
    public String getSplitterName() { return REF; }


    @Override
    public List<Message> wfmSplit(Exchange exchange) {
        long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
        BatchJob job = inProgressJobs.getJob(jobId);
        List<Message> messages = new ArrayList<>();

        if(!job.isCancelled()) {
            // If the job has not been cancelled, perform the split.
            for (Media media : job.getMedia()) {
                if (!media.isFailed()) {
                    Message message = new DefaultMessage();
                    message.setHeader(MpfHeaders.JOB_ID, jobId);
                    message.setHeader(MpfHeaders.MEDIA_ID, media.getId());
                    messages.add(message);
                } else {
                    log.warn("Skipping '{}' ({}). It is in an error state.",
                             media.getUri(), media.getId());
                }
            }
            if (messages.isEmpty()) {
                inProgressJobs.setJobStatus(job.getId(), BatchJobStatusType.ERROR);
            }
        } else {
            log.warn("[Job {}|*|*] Media inspection will not be performed because this job has been cancelled.",
                     job.getId());
        }

        return messages;
    }
}

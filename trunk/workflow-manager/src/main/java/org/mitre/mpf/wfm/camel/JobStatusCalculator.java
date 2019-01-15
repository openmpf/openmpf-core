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

package org.mitre.mpf.wfm.camel;

import org.apache.camel.Exchange;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.Redis;
import org.mitre.mpf.wfm.data.entities.transients.TransientJob;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Job Status Calculator is a tool to calculate the terminal status of a job.
 */
@Component(JobStatusCalculator.REF)
public class JobStatusCalculator {
    public static final String REF = "jobStatusCalculator";

    @Autowired
    private Redis redis;

    @Autowired
    private JsonUtils jsonUtils;

    /**
     * Calculates the terminal status of a batch job
     * @param exchange  An incoming job exchange
     * @return  The terminal JobStatus for the batch job.
     * @throws WfmProcessingException
     */
    public BatchJobStatusType calculateStatus(Exchange exchange) throws WfmProcessingException {
        TransientJob job = jsonUtils.deserialize(exchange.getIn().getBody(byte[].class), TransientJob.class);
        BatchJobStatusType statusFromRedis = redis.getBatchJobStatus(job.getId());
        BatchJobStatusType newStatus = nextStatus(statusFromRedis);
        if (statusFromRedis != newStatus) {
            redis.setJobStatus(job.getId(), newStatus);
        }
        return newStatus;
    }


    private static BatchJobStatusType nextStatus(BatchJobStatusType initialStatus) {
        switch (initialStatus) {
            case ERROR:
            case UNKNOWN:
            case COMPLETE_WITH_ERRORS:
            case COMPLETE_WITH_WARNINGS:
                return initialStatus;
            case IN_PROGRESS_WARNINGS:
                return BatchJobStatusType.COMPLETE_WITH_WARNINGS;
            case IN_PROGRESS_ERRORS:
                return BatchJobStatusType.COMPLETE_WITH_ERRORS;
            case CANCELLING:
                return BatchJobStatusType.CANCELLED;
            default:
                return BatchJobStatusType.COMPLETE;
        }
    }
}

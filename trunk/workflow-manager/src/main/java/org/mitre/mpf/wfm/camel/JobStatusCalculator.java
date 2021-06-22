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

import org.apache.commons.lang3.mutable.Mutable;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;

/**
 * The Job Status Calculator is a tool to calculate the terminal status of a job.
 */
public class JobStatusCalculator {
    private JobStatusCalculator() {
    }

    public static BatchJobStatusType completionStatus(BatchJobStatusType initialStatus) {
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

    public static void checkErrorMessages(JsonOutputObject outputObject,
                                          Mutable<BatchJobStatusType> jobStatus) {
        // Only update a non-terminal status, or a COMPLETE_* status.
        if (!jobStatus.getValue().isTerminal()
                || jobStatus.getValue() == BatchJobStatusType.COMPLETE
                || jobStatus.getValue() == BatchJobStatusType.COMPLETE_WITH_WARNINGS) {

            if (!outputObject.getErrors().isEmpty()) {
                jobStatus.setValue(BatchJobStatusType.COMPLETE_WITH_ERRORS);
                outputObject.setStatus(BatchJobStatusType.COMPLETE_WITH_ERRORS.toString());
                return;
            }

            if (!outputObject.getWarnings().isEmpty()) {
                jobStatus.setValue(BatchJobStatusType.COMPLETE_WITH_WARNINGS);
                outputObject.setStatus(BatchJobStatusType.COMPLETE_WITH_WARNINGS.toString());
            }
        }
    }
}

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

package org.mitre.mpf.wfm.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// BatchJobStatusType enumeration describes all possible job status conditions applicable to a batch job.
public enum BatchJobStatusType {

    /**
     * Default: The status of the job is unknown.
     **/
    UNKNOWN(false),

    /**
     * The job has been initialized but not started.
     */
    INITIALIZED(false),

    /**
     * Indicates that a job was received, but a job could not be created from the contents of
     * the request. For example, this is used when an invalid pipeline is specified.
     */
    JOB_CREATION_ERROR(true),

    /**
     * Indicates the job is in progress.
     */
    IN_PROGRESS(false),

    /**
     * Indicates the job is in progress with errors.
     */
    IN_PROGRESS_ERRORS(false),

    /**
     * Indicates the job is in progress with warnings.
     */
    IN_PROGRESS_WARNINGS(false),

    /**
     * Indicates the job has completed.
     */
    COMPLETE(true),

    /**
     * Indicates the job has completed, but with processing errors.
     */
    COMPLETE_WITH_ERRORS(true),

    /**
     * Indicates the job has completed, but with warnings.
     */
    COMPLETE_WITH_WARNINGS(true),

    /**
     * Indicates the job is in the middle of cancellation.
     */
    CANCELLING(false),

    /**
     * Indicates the job was cancelled as a result of a system shutdown.
     */
    CANCELLED_BY_SHUTDOWN(true),

    /**
     * Indicates the job was cancelled by a user-initiated process.
     */
    CANCELLED(true),

    /**
     * Indicates the job is in an error state. This is used for unknown/unrecoverable
     * errors, and when a piece of media is not available or cannot be retrieved.
     */
    ERROR(true);

    private final boolean _terminal;

    public boolean isTerminal() {
        return _terminal;
    }

    BatchJobStatusType(boolean terminal) { _terminal = terminal; }


    /** Finds the BatchJobStatusType which best matches the given input; if no match is found, {@link #UNKNOWN} is used. */
    public static BatchJobStatusType parse(String input) {
        return parse(input, UNKNOWN);
    }

    public static BatchJobStatusType parse(String input, BatchJobStatusType defaultValue) {
        String trimmed = StringUtils.trimToNull(input);
        for ( BatchJobStatusType jobStatus : BatchJobStatusType.values() ) {
            if ( StringUtils.equalsIgnoreCase(jobStatus.name(), trimmed) ) {
                return jobStatus;
            }
        }
        return defaultValue;
    }

    public static Collection<BatchJobStatusType> getNonTerminalStatuses() {
        List<BatchJobStatusType> jobStatuses = new ArrayList<>();
        for ( BatchJobStatusType jobStatus : BatchJobStatusType.values() ) {
            if (!jobStatus.isTerminal() ) {
                jobStatuses.add(jobStatus);
            }
        }
        return jobStatuses;
    }


}

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

package org.mitre.mpf.wfm.data.entities.persistent;

import org.mitre.mpf.wfm.enums.BatchJobStatusType;

import javax.persistence.*;
import java.time.Instant;

/**
 * This class includes the essential information which describes a batch job. Instances of this class are stored in a
 * persistent data store (as opposed to a transient data store).
 */
@Entity
public class JobRequest {

    /** The unique numeric identifier for this job. */
    @Id
    private long id;
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    /** The timestamp indicating when the server received this job. */
    @Column
    private Instant timeReceived;
    public Instant getTimeReceived() { return timeReceived; }
    public void setTimeReceived(Instant timeReceived) { this.timeReceived = timeReceived; }

    /** The timestamp indicating when the server completed this job.*/
    @Column
    private Instant timeCompleted;
    public Instant getTimeCompleted() { return timeCompleted; }
    public void setTimeCompleted(Instant timeCompleted) { this.timeCompleted = timeCompleted; }

    /** The priority of the job set when creating the job.*/
    @Column
    private int priority;
    public int getPriority() { return  priority; }
    public void setPriority(int priority) { this.priority = priority; }

    /** The current status of this batch job. */
    @Column
    @Enumerated(EnumType.STRING)
    private BatchJobStatusType status = BatchJobStatusType.UNKNOWN;
    public BatchJobStatusType getStatus() { return status; }
    public void setStatus(BatchJobStatusType status) { this.status = status; }

    @Column
    @Lob
    private byte[] job;
    public byte[] getJob() { return job; }
    public void setJob(byte[] job) { this.job = job; }

    @Column
    private String outputObjectPath;
    public String getOutputObjectPath() { return outputObjectPath; }
    public void setOutputObjectPath(String outputObjectPath) { this.outputObjectPath = outputObjectPath; }

    @Column
    private String pipeline;
    public String getPipeline() { return pipeline; }
    public void setPipeline(String pipeline) { this.pipeline = pipeline; }

    /** The version of the output object. */
    @Column
    private String outputObjectVersion;
    public void setOutputObjectVersion(String outputObjectVersion) { this.outputObjectVersion = outputObjectVersion; }

    public String toString() { return String.format("%s#<id='%d'>", this.getClass().getSimpleName(), getId()); }
}

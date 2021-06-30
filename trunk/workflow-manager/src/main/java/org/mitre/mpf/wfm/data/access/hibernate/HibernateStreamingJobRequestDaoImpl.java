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

package org.mitre.mpf.wfm.data.access.hibernate;

import org.mitre.mpf.wfm.data.access.StreamingJobRequestDao;
import org.mitre.mpf.wfm.data.entities.persistent.StreamingJobRequest;
import org.mitre.mpf.wfm.enums.StreamingJobStatusType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.REQUIRED)
@Profile("!docker")
public class HibernateStreamingJobRequestDaoImpl
        extends AbstractHibernateDao<StreamingJobRequest> implements StreamingJobRequestDao {

    private static final Logger log = LoggerFactory.getLogger(
            HibernateStreamingJobRequestDaoImpl.class);

    public HibernateStreamingJobRequestDaoImpl() {
        super(StreamingJobRequest.class);
    }

    @Override
    public void cancelJobsInNonTerminalState() {
        log.info("Marking any remaining running streaming jobs as CANCELLED_BY_SHUTDOWN.");

        var cb = getCriteriaBuilder();
        var update = cb.createCriteriaUpdate(StreamingJobRequest.class);
        var root = update.from(StreamingJobRequest.class);

        update.set("status", StreamingJobStatusType.CANCELLED_BY_SHUTDOWN)
                .set("statusDetail", "Job cancelled due to Workflow Manager shutdown.")
                .where(root.get("status").in(StreamingJobStatusType.getNonTerminalStatuses()));

        var numRowsUpdated = executeUpdate(update);
        if (numRowsUpdated > 0) {
            log.warn("{} streaming jobs were in a non-terminal state and have been marked as {}",
                     numRowsUpdated, StreamingJobStatusType.CANCELLED_BY_SHUTDOWN);
        }
    }
}

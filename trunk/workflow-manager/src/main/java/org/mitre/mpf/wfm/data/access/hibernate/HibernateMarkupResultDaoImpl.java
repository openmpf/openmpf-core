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

import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional(propagation = Propagation.REQUIRED)
public class HibernateMarkupResultDaoImpl
        extends AbstractHibernateDao<MarkupResult> implements MarkupResultDao {

    public HibernateMarkupResultDaoImpl() {
        super(MarkupResult.class);
    }

    @Override
    public Optional<MarkupResult> findByJobIdAndMediaIndex(long jobId, int mediaIndex) {
        var cb = getCriteriaBuilder();
        var query = cb.createQuery(MarkupResult.class);
        var root = query.from(MarkupResult.class);

        query.where(
                cb.equal(root.get("jobId"), jobId),
                cb.equal(root.get("mediaIndex"), mediaIndex));

        return buildQuery(query)
                .setMaxResults(1)
                .list()
                .stream()
                .findFirst();
    }

    @Override
    public List<MarkupResult> findByJobId(long jobId) {
        var cb = getCriteriaBuilder();
        var query = cb.createQuery(MarkupResult.class);
        var root = query.from(MarkupResult.class);

        query.where(cb.equal(root.get("jobId"), jobId));

        return buildQuery(query).list();
    }

    @Override
    public void deleteByJobId(long jobId) {
        var cb = getCriteriaBuilder();
        var delete = cb.createCriteriaDelete(MarkupResult.class);
        var root = delete.from(MarkupResult.class);

        delete.where(cb.equal(root.get("jobId"), jobId));

        executeDelete(delete);
    }
}

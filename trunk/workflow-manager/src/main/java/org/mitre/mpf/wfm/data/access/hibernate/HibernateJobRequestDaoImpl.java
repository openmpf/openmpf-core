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

import org.apache.commons.lang3.StringUtils;
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver;
import org.hibernate.engine.jdbc.dialect.spi.DatabaseMetaDataDialectResolutionInfoAdapter;
import org.mitre.mpf.wfm.data.access.JobRequestDao;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Function;

@Repository
@Transactional(propagation = Propagation.REQUIRED)
public class HibernateJobRequestDaoImpl extends AbstractHibernateDao<JobRequest> implements JobRequestDao {
    private static final Logger LOG = LoggerFactory.getLogger(HibernateJobRequestDaoImpl.class);

    public HibernateJobRequestDaoImpl() {
        super(JobRequest.class);
    }


    @Override
    public void cancelJobsInNonTerminalState() {
        var update = getCriteriaBuilder().createCriteriaUpdate(JobRequest.class);
        var root = update.from(JobRequest.class);

        var nonTerminalStatuses = BatchJobStatusType.getNonTerminalStatuses();
        update.set("status", BatchJobStatusType.CANCELLED_BY_SHUTDOWN)
                .where(root.get("status").in(nonTerminalStatuses));

        int numRowsUpdated = executeUpdate(update);
        if (numRowsUpdated > 0) {
            LOG.warn("{} jobs were in a non-terminal state and have been marked as {}",
                     numRowsUpdated, BatchJobStatusType.CANCELLED_BY_SHUTDOWN);
        }
    }


    @Override
    public List<JobRequest> findByPage(int pageSize,
                                       int offset,
                                       String searchTerm,
                                       String sortColumn,
                                       String sortOrderDirection) {
        var cb = getCriteriaBuilder();
        var query = cb.createQuery(JobRequest.class);
        var root = query.from(JobRequest.class);

        if (!StringUtils.isBlank(searchTerm)) {
            query.where(createSearchFilter(searchTerm, cb, root));
        }

        if (sortOrderDirection.equals("desc")) {
            query.orderBy(cb.desc(root.get(sortColumn)));
        }
        else {
            query.orderBy(cb.asc(root.get(sortColumn)));
        }

        return buildQuery(query)
                .setFirstResult(offset)
                .setMaxResults(pageSize)
                .list();
    }


    @Override
    public long countFiltered(String searchTerm) {
        var cb = getCriteriaBuilder();
        var query = cb.createQuery(Long.class);
        var root = query.from(JobRequest.class);

        query.select(cb.count(root))
                .where(createSearchFilter(searchTerm, cb, root));

        return buildQuery(query).getSingleResult();
    }


    private static Predicate createSearchFilter(String searchTerm, CriteriaBuilder cb,
                                                Root<JobRequest> root) {

        var dateFormat = "YYYY-MM-DD HH24:MI:SS";
        var searchWithWildCard = '%' + searchTerm.toLowerCase() + '%';

        Function<Expression<String>, Predicate> ilike
                = expr -> cb.like(cb.lower(expr), searchWithWildCard);

        return cb.or(
                ilike.apply(root.get("id").as(String.class)),
                ilike.apply(root.get("pipeline")),
                ilike.apply(root.get("status").as(String.class)),
                ilike.apply(cb.function("to_char", String.class, root.get("timeReceived"),
                                        cb.literal(dateFormat))),
                ilike.apply(cb.function("to_char", String.class, root.get("timeCompleted"),
                                        cb.literal(dateFormat)))
        );
    }

    @Override
    public long getNextId() {
        return getCurrentSession().doReturningWork(connection -> {
            var dialectResolver = new StandardDialectResolver();
            var dialect = dialectResolver.resolveDialect(
                    new DatabaseMetaDataDialectResolutionInfoAdapter(connection.getMetaData()));

            var queryString = dialect.getSequenceNextValString("hibernate_sequence");
            try (PreparedStatement preparedStatement = connection.prepareStatement(queryString);
                 ResultSet resultSet = preparedStatement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        });
    }


    @Override
    public void updateStatus(long jobId, BatchJobStatusType status) {
        var cb = getCriteriaBuilder();
        var update = cb.createCriteriaUpdate(JobRequest.class);
        var root = update.from(JobRequest.class);

        update.set("status", status)
                .where(cb.equal(root.get("id"), jobId));

        executeUpdate(update);
    }


    @Override
    public BatchJobStatusType getStatus(long jobId) {
        var cb = getCriteriaBuilder();
        var query = cb.createQuery(BatchJobStatusType.class);
        var root = query.from(JobRequest.class);

        query.select(root.get("status"))
                .where(cb.equal(root.get("id"), jobId));

        return buildQuery(query).getSingleResult();
    }
}

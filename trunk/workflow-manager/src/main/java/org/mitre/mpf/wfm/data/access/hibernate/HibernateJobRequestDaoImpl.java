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
import org.hibernate.Query;
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

@Repository
@Transactional(propagation = Propagation.REQUIRED)
public class HibernateJobRequestDaoImpl extends AbstractHibernateDao<JobRequest> implements JobRequestDao {
    private static final Logger LOG = LoggerFactory.getLogger(HibernateJobRequestDaoImpl.class);

    public HibernateJobRequestDaoImpl() { this.clazz = JobRequest.class; }

    @Override
    public void cancelJobsInNonTerminalState() {
        Query query = getCurrentSession().
                createQuery("UPDATE JobRequest set status = :newStatus where status in (:nonTerminalStatuses)");
        query.setParameter("newStatus", BatchJobStatusType.CANCELLED_BY_SHUTDOWN);
        query.setParameterList("nonTerminalStatuses", BatchJobStatusType.getNonTerminalStatuses());
        int updatedRows = query.executeUpdate();
        if(updatedRows >= 0) {
            LOG.warn("{} jobs were in a non-terminal state and have been marked as {}",
                     updatedRows, BatchJobStatusType.CANCELLED_BY_SHUTDOWN);
        }
    }


    @Override
    public List<JobRequest> findByPage(int pageSize, int offset, String searchTerm, String sortColumn,
                                       String sortOrderDirection) {
        var orderByClause = String.format("order by %s %s", sortColumn, sortOrderDirection);
        Query query;
        if (StringUtils.isBlank(searchTerm)) {
            query = getCurrentSession().createQuery("from JobRequest " + orderByClause);
        }
        else {
            query = createSearchQuery(searchTerm, "", orderByClause);
        }
        return (List<JobRequest>) query.setFirstResult(offset)
                .setMaxResults(pageSize)
                .list();
    }


    @Override
    public long countFiltered(String searchTerm) {
        return (long) createSearchQuery(searchTerm, "select count(*)", "")
                .list()
                .get(0);
    }


    private Query createSearchQuery(String searchTerm, String selectClause, String orderByClause) {
        return getCurrentSession().createQuery(
                selectClause
                        + " from JobRequest"
                        + " where cast(id as string) like :searchTerm"
                        + " or lower(pipeline) like :searchTerm"
                        + " or lower(status) like :searchTerm"
                        + " or to_char(timeReceived, 'YYYY-MM-DD HH24:MI:SS') like :searchTerm"
                        + " or to_char(timeCompleted, 'YYYY-MM-DD HH24:MI:SS') like :searchTerm "
                        + orderByClause)
                .setString("searchTerm", '%' + searchTerm.toLowerCase() + '%');
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
}

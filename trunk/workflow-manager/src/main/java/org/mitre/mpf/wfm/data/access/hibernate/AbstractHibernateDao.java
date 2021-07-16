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

import org.apache.commons.lang3.Validate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.javasimon.SimonManager;
import org.javasimon.Split;
import org.mitre.mpf.wfm.data.access.JpaDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaDelete;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.CriteriaUpdate;
import java.util.Collection;
import java.util.List;

@Repository
@Transactional(propagation = Propagation.REQUIRED)
public abstract class AbstractHibernateDao<T> implements JpaDao<T> {

    /**
     * <p>The type of elements managed by this dao.</p>
     */
    private final Class<T> _clazz;
    private final String _profilerName;

    /** <p>The factory which creates connections to the underlying database.</p> */
    @Autowired
    private SessionFactory sessionFactory;

    protected AbstractHibernateDao(Class<T> clazz) {
        Validate.notNull(clazz);
        _clazz = clazz;
        _profilerName = String.format("%s-%s", getClass().getName(), clazz.getSimpleName());
    }

    @Override
    public T findById(final long id) {
        Split split = SimonManager.getStopwatch(_profilerName + ".findById(long)").start();
        try {
            return (T) getCurrentSession().get(_clazz, id);
        } finally {
            split.stop();
        }
    }

    @Override
    public List<T> findByIds(Collection<Long> ids) {
        Split split = SimonManager.getStopwatch(_profilerName+".findByIds(List)").start();
        try {
            var query = getCriteriaBuilder().createQuery(_clazz);
            var root = query.from(_clazz);
            query.where(root.get("id").in(ids));
            return buildQuery(query).list();
        } finally {
            split.stop();
        }
    }

    @Override
    public void refresh(final T entity) {
        Split split = SimonManager.getStopwatch(_profilerName+".refresh(Object)").start();
        try {
            getCurrentSession().refresh(entity);
        } finally {
            split.stop();
        }
    }

    @Override
    public List<T> findAll() {
        Split split = SimonManager.getStopwatch(_profilerName+".findAll()").start();
        try {
            var query = getCriteriaBuilder().createQuery(_clazz);
            query.from(_clazz);
            return buildQuery(query).list();

        } finally {
            split.stop();
        }
    }

    @Override
    public long countAll() {
        Split split = SimonManager.getStopwatch(_profilerName+".countAll()").start();
        try {
            var cb = getCriteriaBuilder();
            var query = cb.createQuery(Long.class);
            var root = query.from(_clazz);
            query.select(cb.count(root));
            return buildQuery(query)
                    .getSingleResult();
        } finally {
            split.stop();
        }
    }

    @Override
    public T persist(final T entity) {
        Validate.notNull(entity);
        Split split = SimonManager.getStopwatch(_profilerName+".persist(Object)").start();
        try {
            return (T) getCurrentSession().merge(entity);
        } finally {
            split.stop();
        }
    }

    @Override
    public void delete(final T entity) {
        Validate.notNull(entity);
        Split split = SimonManager.getStopwatch(_profilerName+".delete(Object)").start();
        try {
            getCurrentSession().delete(entity);
        } finally {
            split.stop();
        }
    }

    @Override
    public void deleteAll(final Collection<T> entities) {
        Validate.notNull(entities);
        for(T entity : entities) {
            getCurrentSession().delete(entity);
        }
    }

    @Override
    public void deleteById(final long entityId) {
        final T entity = findById(entityId);
        Validate.validState(entity != null);
        Split split = SimonManager.getStopwatch(_profilerName+".deleteById(long)").start();
        try {
            var cb = getCriteriaBuilder();
            var delete = cb.createCriteriaDelete(_clazz);
            var root = delete.from(_clazz);
            delete.where(cb.equal(root.get("id"), entityId));
            executeDelete(delete);
        } finally {
            split.stop();
        }
    }

    @Override
    public void deleteByIds(final Collection<Long> entityIds) {
        var delete = getCriteriaBuilder().createCriteriaDelete(_clazz);
        var root = delete.from(_clazz);
        delete.where(root.get("id").in(entityIds));
        executeDelete(delete);
    }

    @Override
    public T createProxy(long id) {
        Split split = SimonManager.getStopwatch(_profilerName+".createProxy(long)").start();
        try {
            return (T) getCurrentSession().load(_clazz, id);
        } finally {
            split.stop();
        }
    }

    @Override
    public void update(final T entity) {
        Split split = SimonManager.getStopwatch(_profilerName+".update(Object)").start();
        try {
            getCurrentSession().update(entity);
        } finally {
            split.stop();
        }
    }

    public final Session getCurrentSession() {
        Split split = SimonManager.getStopwatch(_profilerName+".getCurrentSession()").start();
        try {
            return sessionFactory.getCurrentSession();
        } finally {
            split.stop();
        }
    }

    public SessionFactory getSessionFactory() {
        Split split = SimonManager.getStopwatch(_profilerName+".getSessionFactory()").start();
        try {
            return sessionFactory;
        } finally {
            split.stop();
        }
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }


    protected <U> Query<U> buildQuery(CriteriaQuery<U> criteriaQuery) {
        return getCurrentSession().createQuery(criteriaQuery);
    }

    protected int executeUpdate(CriteriaUpdate<?> criteriaUpdate) {
        return getCurrentSession().createQuery(criteriaUpdate).executeUpdate();
    }

    protected int executeDelete(CriteriaDelete<?> criteriaDelete) {
        return getCurrentSession().createQuery(criteriaDelete).executeUpdate();
    }

    protected CriteriaBuilder getCriteriaBuilder() {
        return getCurrentSession().getCriteriaBuilder();
    }

}


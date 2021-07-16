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

package org.mitre.mpf.wfm.data.access;

import org.javasimon.aop.Monitored;

import java.util.Collection;
import java.util.List;

@Monitored
public interface JpaDao<T> {

    /** <p>Retrieves an element of the given {@link #getClass() class} by its id.</p> */
    T findById(final long id);

    /** <p>Updates the {@code entity} to reflect any changes which have been made to it in the underlying database since it was last created/retrieved.</p> */
    void refresh(final T entity);

    /** <p>Writes the given {@code entity} to the underlying database.</p> */
    T persist(final T entity);

    /** <p>Retrieves all elements of the given {@link #getClass() class}.</p> */
    List<T> findAll();

    /** <p>Removes the given {@code entity} from the underlying database.</p> */
    void delete(final T entity);
    void deleteAll(final Collection<T> entities);

    /** <p>Removes the entity associated with {@code entityId} from the underlying database.</p> */
    void deleteById(final long entityId);
    void deleteByIds(final Collection<Long> entityIds);

    /** <p>Creates a proxy object for the entity with the given {@code id}.</p> */
    T createProxy(long id);

    /** <p>Creates a proxy object for the entity with the given {@code id}.</p> */
    void update(T entity);

	public List<T> findByIds(Collection<Long> ids);

	public long countAll();
}

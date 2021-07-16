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

import org.mitre.mpf.wfm.data.access.SystemMessageDao;
import org.mitre.mpf.wfm.data.entities.persistent.SystemMessage;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository(HibernateSystemMessageDaoImpl.REF)
@Transactional(propagation = Propagation.REQUIRED)
public class HibernateSystemMessageDaoImpl extends AbstractHibernateDao<SystemMessage> implements SystemMessageDao {
    public static final String REF = "hibernateSystemMessageDaoImpl";

    public HibernateSystemMessageDaoImpl() {
        super(SystemMessage.class);
    }

    public List<SystemMessage> findByType(final String type) {
        List<SystemMessage> msgs = (List<SystemMessage>) getCurrentSession()
                .createQuery("from " + SystemMessage.class.getSimpleName() + " where msgType=:type")
                .setParameter("type", type)
                .list();
        return msgs;
    }

    public List<SystemMessage> findByRemoveStrategy( final String strat ) {
        List<SystemMessage> msgs = (List<SystemMessage>) getCurrentSession()
                .createQuery("from " + SystemMessage.class.getSimpleName() + " where removeStrategy=:strat")
                .setParameter("strat", strat)
                .list();
        return msgs;
    }


    public SystemMessage findById( long id ) {
        List<SystemMessage> objs = (List<SystemMessage>) getCurrentSession()
                .createQuery("from " + SystemMessage.class.getSimpleName() + " where id=:id")
                .setParameter("id", id)
                .list();
        if ( objs.size() > 0 ) {
            return objs.get(0);
        }
        else {
            return null;
        }
    }


    public SystemMessage findByMsgEnum( String msgEnum ) {
        List<SystemMessage> objs = (List<SystemMessage>) getCurrentSession()
                .createQuery("from " + SystemMessage.class.getSimpleName() + " where msgEnum=:msgEnum")
                .setParameter("msgEnum", msgEnum)
                .list();
        if ( objs.size() > 0 ) {
            return objs.get(0);
        }
        else {
            return null;
        }
    }


    public SystemMessage add( SystemMessage obj ) {
        SystemMessage foundObj = this.findById( obj.getId() );
        if ( foundObj == null ) {
            getCurrentSession().save(obj);
            return obj;
        }
        else {
            // for explanation of why merge is necessary, see http://stackoverflow.com/a/11936794/1274852
            Object merged = getCurrentSession().merge( obj );
            getCurrentSession().saveOrUpdate( merged );
            return (SystemMessage) merged;
        }
    }


    public SystemMessage addStandard( String msgEnum ) {
        // check to make sure there isn't another message already in the queue that is the same enum, and delete it
        //  so we can add the new one with the new timestamp
        SystemMessage systemMessage = this.findByMsgEnum( msgEnum );
        if ( systemMessage!=null ) {
            this.delete( systemMessage.getId() );
        }
        systemMessage = new SystemMessage( msgEnum );
        this.add( systemMessage );
        return systemMessage;
    }


    public SystemMessage delete( long msgId ) {
        SystemMessage obj = this.findById( msgId );
        if ( obj != null ) {
            getCurrentSession().delete( obj );
        }
        return obj;
    }


    public SystemMessage delete( String msgEnum ) {
        SystemMessage obj = this.findByMsgEnum( msgEnum );
        if ( obj != null ) {
            getCurrentSession().delete( obj );
        }
        return obj;
    }
}




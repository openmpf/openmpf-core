/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2018 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2018 The MITRE Corporation                                       *
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

package org.mitre.mpf.nms.xml;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import java.util.ArrayList;
import java.util.List;

@XStreamAlias("nodeManager")
public class NodeManager {
    
    @XStreamAsAttribute()
    private String target;

    @XStreamAsAttribute()
    private boolean autoConfigured;

    @XStreamImplicit(itemFieldName="service") 
    private List<Service> services = new ArrayList<Service>();

    /**
     * 
     * @param target JGroup NodeManager Name
     */
    public NodeManager(String target) {
         this.target = target;
    }

    public void setTarget(String target) {
         this.target = target;
    }

    public String getTarget() {
        return this.target;
    }

    public void setAutoConfigured(boolean autoConfigured) {
        this.autoConfigured = autoConfigured;
    }

    public boolean isAutoConfigured() {
        return this.autoConfigured;
    }

    public void add(Service node) {
        services.add(node);
    }

    public List<Service> getServices() {
        return services;
    }
}

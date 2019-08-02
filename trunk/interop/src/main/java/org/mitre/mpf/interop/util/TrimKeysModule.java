/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2019 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2019 The MITRE Corporation                                       *
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

package org.mitre.mpf.interop.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public final class TrimKeysModule extends SimpleModule {

    public TrimKeysModule() {
        // Only trims map keys.
        addKeyDeserializer(String.class, new KeyDeserializer() {
            @Override
            public Object deserializeKey(String key, DeserializationContext context) {
                return StringUtils.trimToEmpty(key);
            }
        });

    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        // When a property with a given propertyName isn't found, try trimming the name and check again.
        context.addDeserializationProblemHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser parser,
                                                 JsonDeserializer<?> deserializer, Object beanOrClass,
                                                 String propertyName) throws IOException {
                if (propertyName == null || !(deserializer instanceof BeanDeserializerBase)) {
                    return false;
                }

                BeanDeserializerBase beanSerializer = (BeanDeserializerBase) deserializer;
                String trimmedPropertyName = propertyName.trim();
                SettableBeanProperty property = beanSerializer.findProperty(trimmedPropertyName);
                if (property == null) {
                    return false;
                }
                property.deserializeAndSet(parser, ctxt, beanOrClass);
                return true;
            }
        });
    }
}

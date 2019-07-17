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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.ser.DefaultSerializerProvider;

public class MpfObjectMapper extends ObjectMapper {

    public MpfObjectMapper() {
        registerInstantModule();
        registerStripWhitespaceModule();
    }

    public MpfObjectMapper(JsonFactory jsonFactory) {
        super(jsonFactory);
        registerInstantModule();
        registerStripWhitespaceModule();
    }

    public MpfObjectMapper(JsonFactory jsonFactory, DefaultSerializerProvider serializerProvider,
                           DefaultDeserializationContext deserializationContext) {
        super(jsonFactory, serializerProvider, deserializationContext);
        registerInstantModule();
        registerStripWhitespaceModule();
    }

    private void registerInstantModule() {
        registerModule(new InstantJsonModule());
    }

    private void registerStripWhitespaceModule() {
        registerModule(new StripWhitespaceModule());
    }

}

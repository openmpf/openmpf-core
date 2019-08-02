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

package org.mitre.mpf.wfm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mitre.mpf.interop.util.MpfObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;


public class TestObjectMapperFactory {


    private static class TestModel {
        public String stringField;

        public List<String> stringListField;

        public Map<String, String> stringMapField;
    }

    private JsonUtils jsonUtils;
    private ObjectMapper mapper;


    @Before
    public void init() {
        mapper = new MpfObjectMapper();
        jsonUtils = new JsonUtils(mapper);
        jsonUtils.init();
    }


    @Test
    public void deserializeClassWithKeyWhitespace() throws IOException {
        ObjectMapper objectMapper = ObjectMapperFactory.customObjectMapper();
        TestModel model1 = new TestModel();
        model1.stringField = " field 1 ";
        model1.stringListField = List.of("  item 1", "item 2      ");
        model1.stringMapField = Map.of("key 1 ", "value 1      \t", "  key 2 ", " value 2");
        String serialized = objectMapper.writeValueAsString(model1);
        System.out.println("Model:" + serialized);

        TestModel deserialized = ObjectMapperFactory.customObjectMapper().readValue(serialized, TestModel.class);
        Assert.assertEquals("field 1", deserialized.stringField);
        Assert.assertEquals(List.of("item 1", "item 2"), deserialized.stringListField);
        Assert.assertEquals(Map.of("key 1", "value 1", "key 2", "value 2"),
                deserialized.stringMapField);

    }

    @Test
    public void deserializeClassWithValueNoWhitespace() throws IOException {
        ObjectMapper objectMapper = ObjectMapperFactory.customObjectMapper();
        TestModel model1 = new TestModel();
        model1.stringField = "field 1";
        model1.stringListField = List.of("item 1", "item 2");
        model1.stringMapField = Map.of("key 1", "value 1", "key 2", "value 2");
        String serialized = objectMapper.writeValueAsString(model1);
        System.out.println("Model:" + serialized);

        TestModel deserialized = ObjectMapperFactory.customObjectMapper().readValue(serialized, TestModel.class);
        Assert.assertEquals("field 1", deserialized.stringField);
        Assert.assertEquals(List.of("item 1", "item 2"), deserialized.stringListField);
        Assert.assertEquals(ImmutableMap.of("key 1", "value 1", "key 2", "value 2"),
                deserialized.stringMapField);
    }

    @Test
    public void deserializeClassFromString() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();

        String stringField = " \t field 1  ";
        List<String> stringListField = List.of("item 1  ", "  item 2");
        Map<String, String> stringMapField = Map.of(" key 1\t", "value 1  ", "  key 2", "    value 2");

        Map<String, Object> model = Map.of("stringField  ", stringField,
                                        "stringListField", stringListField,
                                        "stringMapField", stringMapField);

        String serialized = objectMapper.writeValueAsString(model);

        TestModel deserialized = ObjectMapperFactory.customObjectMapper().readValue(serialized, TestModel.class);
        Assert.assertEquals("field 1", deserialized.stringField);
        Assert.assertEquals(List.of("item 1", "item 2"), deserialized.stringListField);
        Assert.assertEquals(ImmutableMap.of("key 1", "value 1", "key 2", "value 2"),
                deserialized.stringMapField);
    }

}

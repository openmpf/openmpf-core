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
import org.mitre.mpf.rest.api.JobCreationRequest;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.mitre.mpf.wfm.util.ObjectMapperFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public class TestObjectMapperFactory {


    private static class TestModel {
        String stringField;

        List<String> stringListField;

        Map<String, String> stringMapField;
    }

    private static final String KEY_WHITESPACE = "{\n"+
            "\"stringField\": \"value\",\n"+
            "\"stringListField\": [\n"+
            "    \"string1\",\n"+
            "    \"string2\"\n"+
            "  ],\n"+
            "}\n"+
            "\"stringMapField\": {\n"+
            "    \" string1\": \"value\",\n"+
            "    \"string2  \": \"value \",\n"+
            "    \"  string3  \": \"value\"\n"+
            "}";

    private static final String JOB_KEY_WHITESPACE = "{\n"+
            "  \"algorithmProperties\": {\n"+
            "      \"FACECV\": {\n"+
            "         \"FEED_FORWARD_TYPE\": \"FRAME\",\n"+
            "         \"AUTO_ROTATE\": \"true \",\n"+
            "         \"AUTO_FLIP\": \"true\",\n"+
            "         \"MODEL_NAME\": \"TINY YOLO\",\n"+
            "         \"CUDA_DEVICE_ID  \": 0,\n"+
            "         \"MERGE_TRACKS\": \"true\",\n"+
            "         \"MIN_OVERLAP:\": 0.4,\n"+
            "         \"  MIN_GAP_BETWEEN_TRACKS\": \"10\",\n"+
            "         \"  MIN_TRACK_LENGTH \": 2\n"+
            "      }\n"+
            "\n"+
            "},\n"+
            "  \"buildOutput\": \"true\",\n"+
            "  \"callbackMethod\": \"\",\n"+
            "  \"callbackURL\": \"\",\n"+
            "  \"externalId\": \"11\",\n"+
            "  \"jobProperties\": {},\n"+
            "  \"media\": [\n"+
            "    {\n"+
            "      \"mediaUri\": \"file:/home/mpf/openmpf-projects/openmpf/trunk/install/share/remote-media/ferrar-2s-small.mp4\",\n"+
            "      \"properties\": {}\n"+
            "    }\n"+
            "  ],\n"+
            "  \"pipelineName\": \"OCV FACE DETECTION PIPELINE\",\n"+
            "  \"priority\": \"4\"\n"+
            "}";

    private static final String VALUE_WHITESPACE = "{\n"+
            "  \"algorithmProperties\": {\n"+
            "      \"FACECV\": {\n"+
            "         \"FEED_FORWARD_TYPE\": \"FRAME\",\n"+
            "         \"AUTO_ROTATE\": \"true \",\n"+
            "         \"AUTO_FLIP\": \"true\",\n"+
            "         \"MODEL_NAME\": \"TINY YOLO\",\n"+
            "         \"CUDA_DEVICE_ID\":   0  ,\n"+
            "         \"MERGE_TRACKS\": \"true\",\n"+
            "         \"MIN_OVERLAP:\": 0.4 ,\n"+
            "         \"MIN_GAP_BETWEEN_TRACKS\": \"10\",\n"+
            "         \"MIN_TRACK_LENGTH\": 2\n"+
            "      }\n"+
            "\n"+
            "},\n"+
            "  \"buildOutput\": \"true \",\n"+
            "  \"callbackMethod\": \"\",\n"+
            "  \"callbackURL\": \"\",\n"+
            "  \"externalId\": \"11 \",\n"+
            "  \"jobProperties\": {},\n"+
            "  \"media\": [\n"+
            "    {\n"+
            "      \"mediaUri\": \"file:/home/mpf/openmpf-projects/openmpf/trunk/install/share/remote-media/ferrar-2s-small.mp4\",\n"+
            "      \"properties\": {}\n"+
            "    }\n"+
            "  ],\n"+
            "  \"pipelineName\": \"OCV FACE DETECTION PIPELINE\",\n"+
            "  \"priority\": \"4  \"\n"+
            "}";

    private static final String KEY_AND_VALUE_WHITESPACE = "{\n"+
            "  \"algorithmProperties\": {\n"+
            "      \"FACECV\": {\n"+
            "         \"FEED_FORWARD_TYPE\": \"FRAME\",\n"+
            "         \"AUTO_ROTATE\": \"true \",\n"+
            "         \"AUTO_FLIP\": \"true\",\n"+
            "         \"MODEL_NAME\": \"TINY YOLO\",\n"+
            "         \"CUDA_DEVICE_ID  \":   0  ,\n"+
            "         \"MERGE_TRACKS\": \"true\",\n"+
            "         \"MIN_OVERLAP:\": 0.4 ,\n"+
            "         \"  MIN_GAP_BETWEEN_TRACKS\": \"10\",\n"+
            "         \"  MIN_TRACK_LENGTH \": 2\n"+
            "      }\n"+
            "\n"+
            "},\n"+
            "  \"buildOutput\": \"true \",\n"+
            "  \"callbackMethod\": \"\",\n"+
            "  \"callbackURL\": \"\",\n"+
            "  \"externalId\": \"11 \",\n"+
            "  \"jobProperties\": {},\n"+
            "  \"media\": [\n"+
            "    {\n"+
            "      \"mediaUri\": \"file:/home/mpf/openmpf-projects/openmpf/trunk/install/share/remote-media/ferrar-2s-small.mp4\",\n"+
            "      \"properties\": {}\n"+
            "    }\n"+
            "  ],\n"+
            "  \"pipelineName\": \"OCV FACE DETECTION PIPELINE\",\n"+
            "  \"priority\": \"4  \"\n"+
            "}";

    private static final String NO_WHITESPACE = "{\n"+
            "  \"algorithmProperties\": {\n"+
            "      \"FACECV\": {\n"+
            "         \"FEED_FORWARD_TYPE\": \"FRAME\",\n"+
            "         \"AUTO_ROTATE\": \"true \",\n"+
            "         \"AUTO_FLIP\": \"true\",\n"+
            "         \"MODEL_NAME\": \"TINY YOLO\",\n"+
            "         \"CUDA_DEVICE_ID \": 0,\n"+
            "         \"MERGE_TRACKS\": \"true\",\n"+
            "         \"MIN_OVERLAP:\": 0.4,\n"+
            "         \"MIN_GAP_BETWEEN_TRACKS\": \"10\",\n"+
            "         \"MIN_TRACK_LENGTH\": 2\n"+
            "      }\n"+
            "\n"+
            "},\n"+
            "  \"buildOutput\": \"true\",\n"+
            "  \"callbackMethod\": \"\",\n"+
            "  \"callbackURL\": \"\",\n"+
            "  \"externalId\": \"11\",\n"+
            "  \"jobProperties\": {},\n"+
            "  \"media\": [\n"+
            "    {\n"+
            "      \"mediaUri\": \"file:/home/mpf/openmpf-projects/openmpf/trunk/install/share/remote-media/ferrar-2s-small.mp4\",\n"+
            "      \"properties\": {}\n"+
            "    }\n"+
            "  ],\n"+
            "  \"pipelineName\": \"OCV FACE DETECTION PIPELINE\",\n"+
            "  \"priority\": \"4\"\n"+
            "}";

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
        byte[] byteString = KEY_WHITESPACE.getBytes();
//        TestModel model = jsonUtils.deserializeFromText(byteString, TestModel.class);

//        Map<String, String> nestedMap = model.stringMapField;

//        Assert.assertEquals("value", nestedMap.get("string1"));
//        Assert.assertEquals("value", nestedMap.get("string2"));
//        Assert.assertEquals("value", nestedMap.get("string3"));
//        Assert.assertEquals("value", model.stringField);

        ObjectMapper objectMapper = ObjectMapperFactory.customObjectMapper();
        TestModel model1 = new TestModel();
        model1.stringField = " asdf ";
        model1.stringListField = List.of("item 1", "item 2      ");
        model1.stringMapField = ImmutableMap.of("key 1", "value 1", "key 2 ", "value 2");
        String serialized = objectMapper.writeValueAsString(model1);
        System.out.println("Model:" + serialized);

        TestModel deserialized = ObjectMapperFactory.customObjectMapper().readValue(serialized, TestModel.class);
        Assert.assertEquals("asdf", deserialized.stringField);

    }

    //    @Test
    public void deserializeJobWithValueWhitespace() {
        byte[] byteString = KEY_WHITESPACE.getBytes();
        JobCreationRequest jobRequest = jsonUtils.deserializeFromText(byteString, JobCreationRequest.class);

        Map<String, String> properties = jobRequest.getAlgorithmProperties().get("FACECV");

        Assert.assertEquals("value", properties.get("string1"));
        Assert.assertEquals("value", properties.get("string2"));
        Assert.assertEquals("value", properties.get("string3"));
        Assert.assertEquals("11", jobRequest.getExternalId());
    }

}

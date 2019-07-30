package org.mitre.mpf.interop.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public final class TrimValuesModule extends SimpleModule {

    public TrimValuesModule() {
        addDeserializer(String.class, new StdScalarDeserializer<String>(String.class) {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return StringUtils.trimToEmpty(parser.getValueAsString());
            }
        });
    }

}
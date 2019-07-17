package org.mitre.mpf.interop.util;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.commons.lang3.StringUtils;

public final class StripWhitespaceModule extends SimpleModule {

    public StripWhitespaceModule() {
        addKeyDeserializer(String.class, new KeyDeserializer() {
            @Override
            public Object deserializeKey(String key, DeserializationContext context) {
                return StringUtils.trimToNull(key);
            }
        });
    }
}

package com.qaqxianyuqaq.backend.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/** 禁止信任边界中的数字、布尔值或结构被隐式转换为文本。 */
public final class StrictStringDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            return parser.getText();
        }
        return (String) context.handleUnexpectedToken(String.class, parser);
    }
}

package com.qaqxianyuqaq.backend.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 宽容的 LocalDateTime 反序列化器。
 *
 * <p>对外统一使用 UTC 墙钟时间，同时兼容调用方传入带偏移量的 ISO-8601 文本
 * （例如 2026-10-01T09:00:00+08:00），此时会换算为 UTC 后写入。</p>
 */
public class TolerantLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();

        // 1. 带偏移量或 Z 的写法：换算为 UTC 墙钟时间
        try {
            return OffsetDateTime.parse(normalized).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试无偏移量写法
        }

        // 2. 无偏移量写法：按 UTC 语义直接解析
        String withoutSuffix = normalized.endsWith("Z")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        try {
            return LocalDateTime.parse(withoutSuffix);
        } catch (DateTimeParseException exception) {
            throw new IOException("时间格式非法，需为 ISO-8601：" + text, exception);
        }
    }
}

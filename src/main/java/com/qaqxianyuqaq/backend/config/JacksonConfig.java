package com.qaqxianyuqaq.backend.config;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.qaqxianyuqaq.backend.util.TolerantLocalDateTimeDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 配置。
 *
 * <p>全站时间字段使用 UTC 语义的 {@link LocalDateTime}，对外统一序列化为
 * 契约 8.1 规定的形式，例如 2026-10-01T06:24:36Z；反序列化兼容带时区偏移的写法。</p>
 */
@Configuration
public class JacksonConfig {

    /** 对外时间格式：秒级精度，末尾 Z 表示该墙钟时间取自 UTC。 */
    public static final DateTimeFormatter API_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** 注册 LocalDateTime 的序列化与反序列化规则。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(API_TIME_FORMATTER));
            builder.deserializerByType(LocalDateTime.class, new TolerantLocalDateTimeDeserializer());
        };
    }
}

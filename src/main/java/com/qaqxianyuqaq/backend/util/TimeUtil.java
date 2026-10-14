package com.qaqxianyuqaq.backend.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间工具。
 *
 * <p>全站统一使用 UTC 的 {@link LocalDateTime}：数据库按 UTC 墙钟时间存取，
 * 对外文本形如 2026-10-01T06:24:36Z（末尾 Z 表示该墙钟时间取自 UTC）。</p>
 */
public final class TimeUtil {

    /** 对外时间格式，固定使用 UTC 语义。 */
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private TimeUtil() {
    }

    /** 当前时刻（UTC 墙钟时间）。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 格式化为 UTC ISO-8601 文本。 */
    public static String format(LocalDateTime time) {
        return time == null ? null : ISO_FORMATTER.format(time);
    }

    /** 解析 UTC ISO-8601 文本，解析失败返回 null；同时兼容末尾带 Z 的写法。 */
    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}

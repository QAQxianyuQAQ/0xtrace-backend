package com.qaqxianyuqaq.backend.util;

import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** 记录查询的闭区间，输入时区先转换为数据库 UTC 时间。 */
public record RecordTimeRange(LocalDateTime from, LocalDateTime to) {
    public static RecordTimeRange parse(String from, String to) {
        LocalDateTime start = parseTime(from, "from");
        LocalDateTime end = parseTime(to, "to");
        if (start != null && end != null && start.isAfter(end)) {
            throw new ValidationFailedException("to", "结束时间不能早于开始时间");
        }
        return new RecordTimeRange(start, end);
    }

    private static LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException exception) {
            throw new ValidationFailedException(field, "请使用带时区的 ISO-8601 时间");
        }
    }
}

package com.qaqxianyuqaq.backend.domain.dto.admin;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 后台提交记录筛选；时间统一使用带时区的 ISO-8601。 */
public record AdminSubmissionQueryDTO(@Size(max = 40) String eventId, @Size(max = 40) String teamId,
        @Size(max = 40) String userId, @Size(max = 64) String challengeCode,
        @Pattern(regexp = "correct|incorrect|^$") String result,
        @Pattern(regexp = "answer|location|^$") String source, @Size(max = 128) String requestId,
        @Size(max = 40) String from, @Size(max = 40) String to) {
}

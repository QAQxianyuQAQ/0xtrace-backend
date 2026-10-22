package com.qaqxianyuqaq.backend.domain.dto.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 赛事补丁请求：未传字段表示保持不变。 */
public record UpdateEventDTO(
        @Size(max = 128, message = "赛事名称长度不能超过 128")
        String name,
        String status,
        @Size(max = 64, message = "时区标识过长")
        String timezone,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        @Min(value = 1, message = "队伍人数上限至少为 1")
        @Max(value = 100, message = "队伍人数上限不能超过 100")
        Integer maxTeamSize) {
}

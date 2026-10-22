package com.qaqxianyuqaq.backend.domain.dto.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 创建赛事请求：队伍人数上限在创建时必填，后续仍可由管理员调整。 */
public record CreateEventDTO(
        @NotBlank(message = "赛事名称不能为空")
        @Size(max = 128, message = "赛事名称长度不能超过 128")
        String name,
        @Size(max = 64, message = "时区标识过长")
        String timezone,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        @NotNull(message = "队伍人数上限不能为空")
        @Min(value = 1, message = "队伍人数上限至少为 1")
        @Max(value = 100, message = "队伍人数上限不能超过 100")
        Integer maxTeamSize) {
}

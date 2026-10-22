package com.qaqxianyuqaq.backend.domain.dto.contestant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 选手使用队伍唯一标识码加入赛事队伍请求。 */
public record TeamJoinDTO(
        @NotBlank(message = "队伍标识码不能为空")
        @Pattern(regexp = "^[A-Za-z0-9-]{6,32}$", message = "队伍标识码格式不正确")
        String code) {
}

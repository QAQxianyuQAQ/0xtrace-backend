package com.qaqxianyuqaq.backend.domain.dto.contestant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 选手创建赛事队伍请求。 */
public record TeamCreateDTO(
        @NotBlank(message = "队伍名称不能为空")
        @Size(max = 128, message = "队伍名称长度不能超过 128")
        String name) {
}

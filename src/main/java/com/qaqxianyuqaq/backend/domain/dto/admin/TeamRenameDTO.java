package com.qaqxianyuqaq.backend.domain.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 后台队伍改名请求。 */
public record TeamRenameDTO(
        @NotBlank(message = "队伍名称不能为空")
        @Size(max = 128, message = "队伍名称不能超过 128 个字符")
        String name) {
}

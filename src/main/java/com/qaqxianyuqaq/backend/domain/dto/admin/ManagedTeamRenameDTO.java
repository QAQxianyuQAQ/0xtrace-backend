package com.qaqxianyuqaq.backend.domain.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员修改赛事队伍名称请求。 */
public record ManagedTeamRenameDTO(
        @NotBlank(message = "队伍名称不能为空")
        @Size(max = 128, message = "队伍名称长度不能超过 128")
        String name) {
}

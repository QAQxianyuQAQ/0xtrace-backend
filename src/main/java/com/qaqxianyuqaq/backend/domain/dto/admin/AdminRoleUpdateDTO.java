package com.qaqxianyuqaq.backend.domain.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 普通管理员权限变更请求。 */
public record AdminRoleUpdateDTO(
        @NotBlank(message = "角色不能为空")
        @Pattern(regexp = "admin|contestant", message = "仅支持 admin 或 contestant")
        String role) {
}

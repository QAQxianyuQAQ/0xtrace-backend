package com.qaqxianyuqaq.backend.domain.dto.contestant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重置密码请求。 */
public record PasswordResetDTO(
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 256, message = "密码长度需为 8-256 位")
        String password) {
}

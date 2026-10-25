package com.qaqxianyuqaq.backend.domain.dto.contestant;

import jakarta.validation.constraints.NotBlank;
import com.qaqxianyuqaq.backend.util.PasswordBytes;
import jakarta.validation.constraints.Size;

/** 重置密码请求。 */
public record PasswordResetDTO(
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度需为 8-72 位")
        @PasswordBytes String password) {
}

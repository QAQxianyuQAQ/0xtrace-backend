package com.qaqxianyuqaq.backend.domain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录请求：用户名 1-64 位，密码非空且不超过 256 字节。 */
public record LoginDTO(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(max = 256, message = "密码长度不能超过 256")
        String password) {
}

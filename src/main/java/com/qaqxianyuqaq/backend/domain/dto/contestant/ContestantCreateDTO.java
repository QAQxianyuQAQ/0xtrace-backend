package com.qaqxianyuqaq.backend.domain.dto.contestant;

import jakarta.validation.constraints.NotBlank;
import com.qaqxianyuqaq.backend.util.PasswordBytes;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 创建账号请求，密码只用于初始化，服务端以 BCrypt 哈希存储。 */
public record ContestantCreateDTO(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_.-]{1,64}$", message = "用户名仅允许字母、数字、_ . -")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度需为 8-72 位")
        @PasswordBytes String password,
        @Size(max = 64, message = "队伍 ID 过长") String teamId,
        @Size(max = 128, message = "队伍名称过长") String teamName,
        @Size(max = 512, message = "备注过长") String note,
        @Size(max = 32, message = "昵称过长") String nickname) {
}

package com.qaqxianyuqaq.backend.domain.dto.profile;

import jakarta.validation.constraints.Size;

/** 更新昵称、头像 URL；字段为空表示保持不变。 */
public record UpdateProfileDTO(
        @Size(max = 32, message = "昵称长度不能超过 32")
        String nickname,
        @Size(max = 512, message = "头像地址过长")
        String avatarUrl) {
}

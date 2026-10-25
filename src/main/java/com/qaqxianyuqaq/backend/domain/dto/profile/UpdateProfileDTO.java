package com.qaqxianyuqaq.backend.domain.dto.profile;

import jakarta.validation.constraints.Size;

/** 更新昵称、头像 URL；省略或 null 表示保持不变，空串/空白表示清除。 */
public record UpdateProfileDTO(
        @Size(max = 32, message = "昵称长度不能超过 32")
        String nickname,
        @Size(max = 512, message = "头像地址过长")
        String avatarUrl) {
}

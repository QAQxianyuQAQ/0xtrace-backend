package com.qaqxianyuqaq.backend.domain.dto.contestant;

import jakarta.validation.constraints.Size;

/** 编辑账号请求：仅允许白名单资料字段。 */
public record ContestantUpdateDTO(
        @Size(max = 64, message = "队伍 ID 过长") String teamId,
        @Size(max = 128, message = "队伍名称过长") String teamName,
        @Size(max = 512, message = "备注过长") String note,
        @Size(max = 32, message = "昵称过长") String nickname,
        @Size(max = 512, message = "头像地址过长") String avatarUrl,
        String status) {
}

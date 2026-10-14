package com.qaqxianyuqaq.backend.domain.vo.auth;

import java.time.LocalDateTime;

/** 当前会话响应，对应 GET /auth/me。 */
public record MeVO(String userId, String username, String role, String teamId, String teamName, String nickname,
                   String avatarUrl, String status, LocalDateTime lastLoginAt) {
}

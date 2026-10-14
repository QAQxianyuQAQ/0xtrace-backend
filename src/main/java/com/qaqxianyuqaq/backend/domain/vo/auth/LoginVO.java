package com.qaqxianyuqaq.backend.domain.vo.auth;

import java.time.LocalDateTime;

/** 登录成功响应：令牌只通过 HttpOnly Cookie 下发，不进入响应体。 */
public record LoginVO(String userId, String username, String role, String teamId, LocalDateTime expiresAt) {
}

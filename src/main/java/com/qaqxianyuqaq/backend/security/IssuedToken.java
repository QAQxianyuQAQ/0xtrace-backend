package com.qaqxianyuqaq.backend.security;

import java.time.LocalDateTime;

/**
 * 令牌签发结果。
 *
 * @param token     压缩后的 JWT 字符串
 * @param jwtId     令牌 ID，登出时写入黑名单
 * @param expiresAt 过期时间（UTC 墙钟时间）
 */
public record IssuedToken(String token, String jwtId, LocalDateTime expiresAt) {
}

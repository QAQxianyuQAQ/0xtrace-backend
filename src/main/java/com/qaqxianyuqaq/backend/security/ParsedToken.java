package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.domain.enums.UserRole;

import java.time.LocalDateTime;

/**
 * 令牌解析结果。
 *
 * @param jwtId        令牌 ID
 * @param userId       账号主键
 * @param username     账号名
 * @param role         角色
 * @param teamId       所属队伍
 * @param tokenVersion 签发时的会话版本号
 * @param expiresAt    过期时间（UTC 墙钟时间）
 */
public record ParsedToken(String jwtId, String userId, String username, UserRole role, String teamId,
                          int tokenVersion, LocalDateTime expiresAt) {
}

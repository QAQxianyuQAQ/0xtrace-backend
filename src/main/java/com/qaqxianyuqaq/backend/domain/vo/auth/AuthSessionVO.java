package com.qaqxianyuqaq.backend.domain.vo.auth;

/**
 * 登录会话结果。
 *
 * @param token      JWT 令牌，仅由控制器写入 HttpOnly Cookie，绝不进入响应体
 * @param jwtId      令牌 ID，登出时用于黑名单
 * @param ttlSeconds 令牌剩余有效秒数，用于 Cookie Max-Age
 * @param user       登录账号信息
 */
public record AuthSessionVO(String token, String jwtId, long ttlSeconds, LoginVO user) {
}

package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 会话令牌解析组件。
 *
 * <p>令牌优先从 HttpOnly Cookie 读取，同时兼容 Authorization: Bearer，便于服务端联调与自动化测试。</p>
 */
@Component
public class SessionTokenResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TraceQuestProperties properties;

    public SessionTokenResolver(TraceQuestProperties properties) {
        this.properties = properties;
    }

    /** 读取原始令牌字符串。 */
    public Optional<String> resolveRaw(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (properties.getSecurity().getCookieName().equals(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return Optional.of(cookie.getValue());
                }
            }
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }
}

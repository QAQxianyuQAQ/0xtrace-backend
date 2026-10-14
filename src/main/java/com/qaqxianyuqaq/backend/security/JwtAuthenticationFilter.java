package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.service.cache.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器。
 *
 * <p>校验令牌签名、黑名单状态、账号状态与会话版本号，任一不满足即视为未登录。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final SessionTokenResolver tokenResolver;

    private final JwtTokenProvider jwtTokenProvider;

    private final SessionService sessionService;

    private final UserMapper userMapper;

    public JwtAuthenticationFilter(SessionTokenResolver tokenResolver, JwtTokenProvider jwtTokenProvider,
                                   SessionService sessionService, UserMapper userMapper) {
        this.tokenResolver = tokenResolver;
        this.jwtTokenProvider = jwtTokenProvider;
        this.sessionService = sessionService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            tokenResolver.resolveRaw(request).ifPresent(token -> authenticate(token, request));
        }
        chain.doFilter(request, response);
    }

    /** 校验令牌并写入安全上下文。 */
    private void authenticate(String token, HttpServletRequest request) {
        ParsedToken parsed = jwtTokenProvider.parse(token);
        if (parsed == null || sessionService.isRevoked(parsed.jwtId())) {
            return;
        }
        // 回查账号，保证停用账号与改密后的旧令牌立即失效
        User user = userMapper.selectById(parsed.userId());
        if (user == null || user.getStatus() != AccountStatus.ACTIVE) {
            return;
        }
        int currentVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (currentVersion != parsed.tokenVersion()) {
            return;
        }
        UserPrincipal principal = new UserPrincipal(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.domain.vo.common.ErrorVO;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.util.RequestUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * 同源校验过滤器。
 *
 * <p>会话通过 HttpOnly Cookie 传递，写请求必须来自同源地址，用于替代前端可绕过的 CSRF 令牌。</p>
 */
@Component
public class SameOriginFilter extends OncePerRequestFilter {

    /** 仅对状态变更方法做来源校验。 */
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 需要校验的接口前缀。 */
    private static final String API_PREFIX = "/api/";

    private final TraceQuestProperties properties;

    private final ObjectMapper objectMapper;

    public SameOriginFilter(TraceQuestProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!requiresCheck(request)) {
            chain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader("Origin");
        if (!isAllowed(origin, request)) {
            response.setStatus(ErrorCode.ORIGIN_REJECTED.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ErrorVO.of(ErrorCode.ORIGIN_REJECTED.code(),
                    "请求来源校验失败", RequestUtil.currentRequestId()));
            return;
        }
        chain.doFilter(request, response);
    }

    /** 判断本次请求是否需要来源校验。 */
    private boolean requiresCheck(HttpServletRequest request) {
        if (!properties.getSecurity().isEnforceSameOrigin()) {
            return false;
        }
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return path != null && path.startsWith(API_PREFIX);
    }

    /** 校验 Origin 是否与请求主机同源或在白名单内。 */
    private boolean isAllowed(String origin, HttpServletRequest request) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        List<String> allowed = properties.getSecurity().getAllowedOrigins();
        if (allowed != null) {
            for (String candidate : allowed) {
                if (candidate != null && candidate.equalsIgnoreCase(origin)) {
                    return true;
                }
            }
        }
        try {
            String host = URI.create(origin).getHost();
            String requestHost = request.getHeader("Host");
            if (host == null || requestHost == null) {
                return false;
            }
            String requestHostName = requestHost.contains(":")
                    ? requestHost.substring(0, requestHost.indexOf(':')) : requestHost;
            return host.equalsIgnoreCase(requestHostName);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

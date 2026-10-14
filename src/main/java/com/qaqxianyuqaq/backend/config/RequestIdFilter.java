package com.qaqxianyuqaq.backend.config;

import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.RequestUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求 ID 过滤器。
 *
 * <p>为每个请求生成 requestId 并写入 MDC 与响应头，供错误响应与日志串联使用。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 响应头名称，便于前端与排障时定位请求。 */
    private static final String RESPONSE_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = IdGenerator.requestId();
        MDC.put(RequestUtil.REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(RESPONSE_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 清理 MDC，避免线程复用导致的请求 ID 串号
            MDC.remove(RequestUtil.REQUEST_ID_MDC_KEY);
        }
    }
}

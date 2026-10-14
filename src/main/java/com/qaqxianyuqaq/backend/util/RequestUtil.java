package com.qaqxianyuqaq.backend.util;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

/**
 * HTTP 请求辅助工具。
 *
 * <p>集中处理请求 ID 与客户端地址解析，业务层不得直接依赖 Servlet 请求对象。</p>
 */
public final class RequestUtil {

    /** MDC 中保存请求 ID 的键名，与日志格式保持一致。 */
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    /** 反向代理场景下由网关写入的真实客户端地址头。 */
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private RequestUtil() {
    }

    /** 获取当前请求 ID，缺失时返回 null。 */
    public static String currentRequestId() {
        return MDC.get(REQUEST_ID_MDC_KEY);
    }

    /** 解析客户端地址，兼容反向代理的 X-Forwarded-For。 */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

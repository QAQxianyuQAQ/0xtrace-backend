package com.qaqxianyuqaq.backend.aspect;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.security.SecurityUtils;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.RateLimitService;
import com.qaqxianyuqaq.backend.util.RequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 限流切面。
 *
 * <p>把限流从业务实现中抽离，阈值统一由 tracequest.rate-limit 配置管理。</p>
 */
@Aspect
@Component
@Order(10)
public class RateLimitAspect {

    /** 未在配置中登记的作用域使用的默认阈值。 */
    private static final int DEFAULT_LIMIT = 60;

    private final RateLimitService rateLimitService;

    private final TraceQuestProperties properties;

    public RateLimitAspect(RateLimitService rateLimitService, TraceQuestProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    /** 前置通知：按作用域与主体消耗配额，超限抛出 429。 */
    @Before("@annotation(rateLimit)")
    public void enforce(RateLimit rateLimit) {
        String subject = resolveSubject(rateLimit);
        rateLimitService.checkAndConsume(rateLimit.scope(), subject, limitOf(rateLimit.scope()));
    }

    /** 解析限流主体。 */
    private String resolveSubject(RateLimit rateLimit) {
        UserPrincipal principal = SecurityUtils.currentPrincipalOrNull();
        String clientIp = clientIp();
        return switch (rateLimit.subject()) {
            case USER -> principal == null ? clientIp : principal.userId();
            case IP -> clientIp;
            case USER_OR_IP -> principal == null ? clientIp : principal.userId();
        };
    }

    /** 按作用域读取配置阈值。 */
    private int limitOf(String scope) {
        return switch (scope) {
            case "submission" -> properties.getRateLimit().getSubmissionPerMinute();
            case "location-verify" -> properties.getRateLimit().getLocationVerifyPerMinute();
            case "admin-write" -> properties.getRateLimit().getAdminWritePerMinute();
            default -> DEFAULT_LIMIT;
        };
    }

    /** 获取客户端地址。 */
    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return RequestUtil.clientIp(request);
        }
        return "unknown";
    }
}

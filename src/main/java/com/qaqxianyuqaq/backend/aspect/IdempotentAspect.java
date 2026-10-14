package com.qaqxianyuqaq.backend.aspect;

import com.qaqxianyuqaq.backend.security.SecurityUtils;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.IdempotencyService;
import com.qaqxianyuqaq.backend.util.DigestUtil;
import com.qaqxianyuqaq.backend.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * 幂等切面。
 *
 * <p>读取 Idempotency-Key 请求头，命中历史记录时直接回放首次响应，避免重复计分。</p>
 */
@Aspect
@Component
@Order(20)
public class IdempotentAspect {

    /** 幂等键请求头名称，与契约 8.1 一致。 */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final IdempotencyService idempotencyService;

    public IdempotentAspect(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    /** 环绕通知：按需回放或记录响应快照。 */
    @Around("@annotation(idempotent)")
    public Object apply(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String idempotencyKey = header(IDEMPOTENCY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 未携带幂等键时按普通请求处理，业务侧仍可用 requestId 兜底
            return joinPoint.proceed();
        }
        UserPrincipal principal = SecurityUtils.currentPrincipalOrNull();
        String userId = principal == null ? "" : principal.userId();
        String requestHash = DigestUtil.sha256Hex(JsonUtil.toJson(joinPoint.getArgs()));

        // 1. 命中历史记录时直接回放首次响应
        Optional<String> cached = idempotencyService.findResponse(idempotent.scope(), idempotencyKey, userId,
                requestHash);
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        if (cached.isPresent()) {
            Object replayed = JsonUtil.fromJson(cached.get(), signature.getReturnType());
            if (replayed != null || signature.getReturnType() == void.class) {
                return replayed;
            }
        }

        // 2. 未命中时执行目标方法，并把成功响应写入幂等记录
        Object result = joinPoint.proceed();
        idempotencyService.saveResponse(idempotent.scope(), idempotencyKey, userId, requestHash,
                JsonUtil.toJson(result));
        return result;
    }

    /** 读取当前请求头。 */
    private String header(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getHeader(name);
        }
        return null;
    }
}

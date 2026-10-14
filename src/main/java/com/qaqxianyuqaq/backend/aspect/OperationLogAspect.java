package com.qaqxianyuqaq.backend.aspect;

import com.qaqxianyuqaq.backend.util.RequestUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 操作日志切面。
 *
 * <p>统一记录服务层方法耗时，慢调用按 WARN 输出，避免在业务实现里内联日志代码。</p>
 */
@Aspect
@Component
@Order(40)
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    /** 慢调用阈值（毫秒）。 */
    private static final long SLOW_THRESHOLD_MILLIS = 300;

    /** 环绕通知：记录方法名、耗时与请求 ID。 */
    @Around("execution(* com.qaqxianyuqaq.backend.service.impl..*(..))")
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAt = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long cost = System.currentTimeMillis() - startedAt;
            String target = joinPoint.getSignature().getDeclaringType().getSimpleName() + "."
                    + joinPoint.getSignature().getName();
            String requestId = RequestUtil.currentRequestId();
            if (cost >= SLOW_THRESHOLD_MILLIS) {
                log.warn("慢调用: {} 耗时 {}ms, requestId={}", target, cost, requestId);
            } else {
                log.debug("服务调用: {} 耗时 {}ms, requestId={}", target, cost, requestId);
            }
        }
    }
}

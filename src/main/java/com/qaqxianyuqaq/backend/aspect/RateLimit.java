package com.qaqxianyuqaq.backend.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流标记。
 *
 * <p>限流阈值来自 tracequest.rate-limit 配置，具体作用域由 scope 决定。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流作用域，例如 submission、location-verify、admin-write。 */
    String scope();

    /** 限流主体类型。 */
    RateLimitSubject subject() default RateLimitSubject.USER_OR_IP;
}

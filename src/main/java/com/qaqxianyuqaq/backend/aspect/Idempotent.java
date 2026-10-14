package com.qaqxianyuqaq.backend.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等标记。
 *
 * <p>请求携带 Idempotency-Key 时，同一键与同一请求内容重放直接回放首次成功响应。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 幂等作用域，用于隔离不同接口的幂等键空间。 */
    String scope();
}

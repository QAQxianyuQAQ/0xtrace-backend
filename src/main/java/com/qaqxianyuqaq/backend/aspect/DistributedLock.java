package com.qaqxianyuqaq.backend.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁标记。
 *
 * <p>用于解题计分等并发写路径，key 支持 SpEL 表达式引用方法参数。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /** SpEL 锁键表达式，例如 #principal.userId() + ':' + #challenge.id。 */
    String key();

    /** 获取锁的最大等待时间（毫秒），超时后按冲突处理。 */
    long waitMillis() default 3000;

    /** 锁自动释放时间（毫秒），避免死锁。 */
    long leaseMillis() default 10000;
}

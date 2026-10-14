package com.qaqxianyuqaq.backend.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计动作标记。
 *
 * <p>标注在管理端接口方法上，由 {@link AuditAspect} 统一记录操作者、对象与请求 ID。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditAction {

    /** 动作标识，例如 challenge.published。 */
    String value();

    /** 操作对象类型，例如 challenge、contestant、event。 */
    String entityType();

    /** 作为对象 ID 的方法参数名；留空时尝试从返回值中提取 code/id。 */
    String entityIdParam() default "";
}

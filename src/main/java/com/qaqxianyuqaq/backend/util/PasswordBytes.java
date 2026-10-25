package com.qaqxianyuqaq.backend.util;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;
/** 对所有 BCrypt 入口使用同一 UTF-8 字节限制，禁止隐式截断。 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordBytesValidator.class)
public @interface PasswordBytes {
    String message() default "密码最多为 72 个 UTF-8 字节";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

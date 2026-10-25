package com.qaqxianyuqaq.backend.util;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;
/** 空值由必填约束处理，此处只校验 BCrypt 的字节上限。 */
public class PasswordBytesValidator implements ConstraintValidator<PasswordBytes, String> {
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}

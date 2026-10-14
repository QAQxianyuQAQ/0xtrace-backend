package com.qaqxianyuqaq.backend.util;

import com.qaqxianyuqaq.backend.exception.ValidationFailedException;

/**
 * 枚举转换工具。
 *
 * <p>接口层使用小写字符串（如 open、published），持久层使用大写枚举名。</p>
 */
public final class EnumUtil {

    private EnumUtil() {
    }

    /** 将接口字符串转换为枚举，非法值抛出字段校验异常。 */
    public static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException(field, "不支持的取值：" + value);
        }
    }

    /** 将枚举转换为接口小写字符串。 */
    public static String toApi(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase();
    }
}

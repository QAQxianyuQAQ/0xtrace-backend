package com.qaqxianyuqaq.backend.domain.vo.common;

import java.util.List;

/** 统一错误响应体：8.1 约定的 error 对象与 requestId。 */
public record ErrorVO(ErrorDetailVO error, String requestId) {

    /** 构造无字段明细的错误响应。 */
    public static ErrorVO of(String code, String message, String requestId) {
        return new ErrorVO(new ErrorDetailVO(code, message, List.of()), requestId);
    }

    /** 构造带字段明细的错误响应。 */
    public static ErrorVO of(String code, String message, List<FieldErrorVO> details, String requestId) {
        return new ErrorVO(new ErrorDetailVO(code, message, details), requestId);
    }
}

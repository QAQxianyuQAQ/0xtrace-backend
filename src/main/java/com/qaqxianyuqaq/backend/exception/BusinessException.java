package com.qaqxianyuqaq.backend.exception;

import com.qaqxianyuqaq.backend.domain.vo.common.FieldErrorVO;

import java.util.List;

/**
 * 业务异常基类。
 *
 * <p>业务异常只承载错误码与可对外披露的描述，由全局异常处理器统一转换为错误响应。</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    private final transient List<FieldErrorVO> details;

    /** 以错误码默认描述构造异常。 */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    /** 以自定义描述构造异常。 */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    /** 以字段明细构造异常。 */
    public BusinessException(ErrorCode errorCode, String message, List<FieldErrorVO> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /** 错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 字段级错误明细。 */
    public List<FieldErrorVO> details() {
        return details;
    }
}

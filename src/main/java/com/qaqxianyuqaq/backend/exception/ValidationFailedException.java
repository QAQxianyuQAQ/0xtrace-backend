package com.qaqxianyuqaq.backend.exception;

import com.qaqxianyuqaq.backend.domain.vo.common.FieldErrorVO;

import java.util.List;

/** 字段校验失败异常，携带字段与原因明细。 */
public class ValidationFailedException extends BusinessException {

    /** 单字段校验失败。 */
    public ValidationFailedException(String field, String reason) {
        super(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                List.of(new FieldErrorVO(field, reason)));
    }

    /** 多字段校验失败。 */
    public ValidationFailedException(List<FieldErrorVO> details) {
        super(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), details);
    }
}

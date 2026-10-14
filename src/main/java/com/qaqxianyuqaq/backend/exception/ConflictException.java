package com.qaqxianyuqaq.backend.exception;

/** 状态冲突异常：重复编号、幂等键冲突、赛事状态非法流转等。 */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}

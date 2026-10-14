package com.qaqxianyuqaq.backend.exception;

/** 对象存储异常：MinIO 不可用或读写失败。 */
public class StorageException extends BusinessException {

    public StorageException(String message) {
        super(ErrorCode.STORAGE_ERROR, message);
    }

    public StorageException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}

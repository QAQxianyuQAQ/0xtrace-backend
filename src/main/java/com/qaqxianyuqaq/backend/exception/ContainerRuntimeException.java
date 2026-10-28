package com.qaqxianyuqaq.backend.exception;

/** 题目容器运行时异常：只返回稳定的对外错误，不泄漏 Docker 命令详情。 */
public class ContainerRuntimeException extends BusinessException {

    public ContainerRuntimeException(String message) {
        super(ErrorCode.CONTAINER_RUNTIME_ERROR, message);
    }
}

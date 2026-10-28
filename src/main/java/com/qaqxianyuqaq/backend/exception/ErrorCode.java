package com.qaqxianyuqaq.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。
 *
 * <p>错误码与 8.1 节约定的 HTTP 状态一一对应，禁止在业务代码中直接硬编码状态码文本。</p>
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求字段无效"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "权限不足"),
    ORIGIN_REJECTED(HttpStatus.FORBIDDEN, "请求来源校验失败"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号已停用"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CHALLENGE_NOT_FOUND(HttpStatus.NOT_FOUND, "题目不存在或不可见"),
    CONFLICT(HttpStatus.CONFLICT, "状态冲突"),
    CODE_DUPLICATED(HttpStatus.CONFLICT, "题目编号已存在"),
    CHALLENGE_HAS_SOLVES(HttpStatus.CONFLICT, "题目已有人解出，禁止硬删除"),
    EVENT_STATE_CONFLICT(HttpStatus.CONFLICT, "赛事状态流转不合法"),
    SUBMISSION_CLOSED(HttpStatus.CONFLICT, "当前不在可作答时间窗内"),
    CHALLENGE_NOT_ANSWERABLE(HttpStatus.CONFLICT, "题目当前不可作答"),
    CONTAINER_NOT_READY(HttpStatus.CONFLICT, "题目容器尚未就绪"),
    FLAG_OWNER_MISMATCH(HttpStatus.FORBIDDEN, "串通flag并不是好习惯"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "幂等键复用但请求内容冲突"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件或请求体过大"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的媒体类型"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的文件类型"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "业务校验失败"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "对象存储不可用"),
    CONTAINER_RUNTIME_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "题目容器暂不可用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务端错误"),
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "该能力尚未实现");

    private final HttpStatus status;

    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    /** HTTP 状态码。 */
    public HttpStatus status() {
        return status;
    }

    /** 错误码默认描述。 */
    public String defaultMessage() {
        return defaultMessage;
    }

    /** 错误码名称。 */
    public String code() {
        return name();
    }
}

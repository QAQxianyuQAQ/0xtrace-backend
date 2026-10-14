package com.qaqxianyuqaq.backend.exception;

/** 触发限流异常，携带建议重试秒数。 */
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Retry-After 响应头取值。 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

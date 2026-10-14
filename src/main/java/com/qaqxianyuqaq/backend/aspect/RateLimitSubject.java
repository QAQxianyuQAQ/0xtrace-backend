package com.qaqxianyuqaq.backend.aspect;

/** 限流主体类型：决定限流计数按账号还是按客户端地址。 */
public enum RateLimitSubject {

    /** 按当前登录账号限流。 */
    USER,

    /** 按客户端地址限流。 */
    IP,

    /** 已登录按账号、未登录按客户端地址。 */
    USER_OR_IP
}

package com.qaqxianyuqaq.backend.service.cache;

/** 限流服务：固定窗口计数，超限抛出 429 业务异常。 */
public interface RateLimitService {

    /**
     * 校验并消耗一次配额。
     *
     * @param scope          限流作用域，例如 login、submission
     * @param subject        限流主体，例如客户端地址或账号 ID
     * @param limitPerMinute 每分钟允许次数
     */
    void checkAndConsume(String scope, String subject, int limitPerMinute);

    /** 记录登录失败次数，达到阈值后账号+地址组合进入临时锁定。 */
    void recordLoginFailure(String clientIp, String username);

    /** 判断登录是否处于临时锁定状态。 */
    boolean isLoginLocked(String clientIp, String username);

    /** 登录成功后清除失败计数。 */
    void clearLoginFailure(String clientIp, String username);
}

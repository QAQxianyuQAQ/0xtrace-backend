package com.qaqxianyuqaq.backend.service.cache;

/** 会话服务：令牌黑名单与账号会话版本管理。 */
public interface SessionService {

    /** 将令牌加入黑名单，过期时间与令牌剩余有效期一致。 */
    void revoke(String jwtId, long ttlSeconds);

    /** 判断令牌是否已被撤销。 */
    boolean isRevoked(String jwtId);

    /** 自增账号会话版本，使该账号全部已签发令牌立即失效（停用、改密时调用）。 */
    int bumpTokenVersion(String userId);
}

package com.qaqxianyuqaq.backend.service.cache.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.cache.SessionService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 会话服务实现：令牌黑名单与会话版本号。 */
@Service
public class SessionServiceImpl implements SessionService {

    /** 黑名单标记值，仅用于判断键是否存在。 */
    private static final String REVOKED = "1";

    private final CacheService cacheService;

    private final UserMapper userMapper;

    public SessionServiceImpl(CacheService cacheService, UserMapper userMapper) {
        this.cacheService = cacheService;
        this.userMapper = userMapper;
    }

    @Override
    public void revoke(String jwtId, long ttlSeconds) {
        if (jwtId == null || ttlSeconds <= 0) {
            return;
        }
        // 黑名单存活时间与令牌剩余有效期一致，令牌自然过期后键也失效
        cacheService.set(CacheKeys.tokenBlacklist(jwtId), REVOKED, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public boolean isRevoked(String jwtId) {
        if (jwtId == null) {
            return false;
        }
        return cacheService.get(CacheKeys.tokenBlacklist(jwtId)).isPresent();
    }

    @Override
    public int bumpTokenVersion(String userId) {
        // 基于数据库自增，保证多实例部署下会话失效结果一致
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .setSql("token_version = token_version + 1"));
        User user = userMapper.selectById(userId);
        return user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }
}

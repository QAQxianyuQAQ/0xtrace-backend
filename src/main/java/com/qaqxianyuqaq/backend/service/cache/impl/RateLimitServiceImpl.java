package com.qaqxianyuqaq.backend.service.cache.impl;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.exception.RateLimitedException;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.cache.RateLimitService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 限流服务实现：基于缓存计数器的固定窗口限流。 */
@Service
public class RateLimitServiceImpl implements RateLimitService {

    /** 计数窗口固定为 1 分钟。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final CacheService cacheService;

    private final TraceQuestProperties properties;

    public RateLimitServiceImpl(CacheService cacheService, TraceQuestProperties properties) {
        this.cacheService = cacheService;
        this.properties = properties;
    }

    @Override
    public void checkAndConsume(String scope, String subject, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return;
        }
        // 计数键按作用域与主体隔离，窗口结束后自动过期
        long current = cacheService.increment(CacheKeys.rateLimit(scope, subject), WINDOW);
        if (current > limitPerMinute) {
            throw new RateLimitedException(WINDOW.toSeconds());
        }
    }

    @Override
    public void recordLoginFailure(String clientIp, String username) {
        // 失败计数窗口远大于限流窗口，用于抵御撞库
        cacheService.increment(CacheKeys.loginFailure(clientIp, username),
                properties.getRateLimit().getLoginFailureWindow());
    }

    @Override
    public boolean isLoginLocked(String clientIp, String username) {
        long failures = cacheService.get(CacheKeys.loginFailure(clientIp, username))
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException exception) {
                        return 0L;
                    }
                })
                .orElse(0L);
        return failures >= properties.getRateLimit().getLoginFailureThreshold();
    }

    @Override
    public void clearLoginFailure(String clientIp, String username) {
        cacheService.delete(CacheKeys.loginFailure(clientIp, username));
    }
}

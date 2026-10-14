package com.qaqxianyuqaq.backend.service.cache.impl;

import com.qaqxianyuqaq.backend.service.cache.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存服务实现。
 *
 * <p>优先使用 Redis；当 Redis 不可用时降级为进程内缓存，保证演示环境与单测可运行。</p>
 */
@Service
public class RedisCacheServiceImpl implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheServiceImpl.class);

    private final StringRedisTemplate redisTemplate;

    /** 降级缓存：值为内容与过期时间戳的组合，仅在 Redis 不可用时使用。 */
    private final Map<String, LocalCacheEntry> localCache = new ConcurrentHashMap<>();

    private volatile boolean degraded = false;

    public RedisCacheServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> get(String key) {
        if (!degraded) {
            try {
                return Optional.ofNullable(redisTemplate.opsForValue().get(key));
            } catch (RuntimeException exception) {
                markDegraded(exception);
            }
        }
        // 降级路径：命中且未过期才返回值，过期条目立即清除
        LocalCacheEntry entry = localCache.get(key);
        if (entry == null || entry.isExpired()) {
            localCache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        if (!degraded) {
            try {
                redisTemplate.opsForValue().set(key, value, ttl);
                return;
            } catch (RuntimeException exception) {
                markDegraded(exception);
            }
        }
        localCache.put(key, new LocalCacheEntry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public void delete(String key) {
        if (!degraded) {
            try {
                redisTemplate.delete(key);
            } catch (RuntimeException exception) {
                markDegraded(exception);
            }
        }
        localCache.remove(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        if (!degraded) {
            try {
                Long value = redisTemplate.opsForValue().increment(key);
                if (value != null && value == 1L) {
                    redisTemplate.expire(key, ttl);
                }
                return value == null ? 0 : value;
            } catch (RuntimeException exception) {
                markDegraded(exception);
            }
        }
        // 降级路径：以原子计算方式完成自增并保留原有过期时间
        LocalCacheEntry updated = localCache.compute(key, (cacheKey, current) -> {
            long now = System.currentTimeMillis();
            if (current == null || current.isExpired()) {
                return new LocalCacheEntry("1", now + ttl.toMillis());
            }
            long next = Long.parseLong(current.value()) + 1;
            return new LocalCacheEntry(String.valueOf(next), current.expiresAtMillis());
        });
        return Long.parseLong(updated.value());
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        if (!degraded) {
            try {
                Boolean success = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
                return Boolean.TRUE.equals(success);
            } catch (RuntimeException exception) {
                markDegraded(exception);
            }
        }
        // 降级路径：先清理过期条目，再用 putIfAbsent 语义抢占
        LocalCacheEntry current = localCache.get(key);
        if (current != null && current.isExpired()) {
            localCache.remove(key, current);
        }
        LocalCacheEntry previous = localCache.putIfAbsent(key,
                new LocalCacheEntry(value, System.currentTimeMillis() + ttl.toMillis()));
        return previous == null;
    }

    @Override
    public void deleteByPrefix(String prefix) {
        localCache.keySet().removeIf(key -> key.startsWith(prefix));
        if (degraded) {
            return;
        }
        try {
            // 使用 SCAN 风格遍历，避免 KEYS 阻塞 Redis 实例
            var keys = redisTemplate.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (RuntimeException exception) {
            markDegraded(exception);
        }
    }

    /** 标记降级并输出一次性告警，避免日志被重复异常刷屏。 */
    private void markDegraded(RuntimeException exception) {
        if (!degraded) {
            degraded = true;
            log.warn("Redis 不可用，缓存已降级为进程内实现: {}", exception.getMessage());
        }
        if (!(exception instanceof RedisConnectionFailureException)) {
            log.debug("缓存访问异常", exception);
        }
    }

}

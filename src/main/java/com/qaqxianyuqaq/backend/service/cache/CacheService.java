package com.qaqxianyuqaq.backend.service.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存服务。
 *
 * <p>以 Redis 为一级缓存；Redis 不可用时降级为进程内缓存，保证单实例演示环境可用。</p>
 */
public interface CacheService {

    /** 读取字符串值。 */
    Optional<String> get(String key);

    /** 写入字符串值并设置过期时间。 */
    void set(String key, String value, Duration ttl);

    /** 删除键。 */
    void delete(String key);

    /** 自增计数并在首次写入时设置过期时间，返回自增后的值。 */
    long increment(String key, Duration ttl);

    /** 仅当键不存在时写入，返回是否写入成功（分布式锁与幂等抢占使用）。 */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /** 按前缀批量删除，用于排行榜缓存失效。 */
    void deleteByPrefix(String prefix);
}

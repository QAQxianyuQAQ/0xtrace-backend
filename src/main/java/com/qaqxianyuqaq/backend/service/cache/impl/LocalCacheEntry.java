package com.qaqxianyuqaq.backend.service.cache.impl;

/**
 * 进程内降级缓存条目。
 *
 * @param value           缓存内容
 * @param expiresAtMillis 过期时间戳（毫秒）
 */
record LocalCacheEntry(String value, long expiresAtMillis) {

    /** 判断是否已过期。 */
    boolean isExpired() {
        return expiresAtMillis <= System.currentTimeMillis();
    }
}

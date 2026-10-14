package com.qaqxianyuqaq.backend.service.cache;

import java.util.Optional;

/**
 * 幂等服务。
 *
 * <p>写请求携带 Idempotency-Key 时，同一键与同一请求内容重放直接返回首次响应快照。</p>
 */
public interface IdempotencyService {

    /** 查询已保存的响应快照；键相同但请求内容不同时抛出 409 冲突。 */
    Optional<String> findResponse(String scope, String idempotencyKey, String userId, String requestHash);

    /** 保存首次成功响应快照。 */
    void saveResponse(String scope, String idempotencyKey, String userId, String requestHash, String responseJson);
}

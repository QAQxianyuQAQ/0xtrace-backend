package com.qaqxianyuqaq.backend.service.cache.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.domain.entity.IdempotencyRecord;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.repository.common.IdempotencyRecordMapper;
import com.qaqxianyuqaq.backend.service.cache.IdempotencyService;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 幂等服务实现：以数据库记录为准，保证重试不重复计分。 */
@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyRecordMapper idempotencyRecordMapper;

    public IdempotencyServiceImpl(IdempotencyRecordMapper idempotencyRecordMapper) {
        this.idempotencyRecordMapper = idempotencyRecordMapper;
    }

    @Override
    public Optional<String> findResponse(String scope, String idempotencyKey, String userId, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        // 0. 唯一键认领与响应保存处在切面事务内；并发请求必须先等待提交或回滚。
        IdempotencyRecord reservation = new IdempotencyRecord();
        reservation.setId(IdGenerator.next("idem"));
        reservation.setScope(scope);
        reservation.setIdempotencyKey(idempotencyKey);
        reservation.setUserId(userId == null ? "" : userId);
        reservation.setRequestHash(requestHash);
        reservation.setCreatedAt(TimeUtil.now());
        idempotencyRecordMapper.claim(reservation);
        // 1. 幂等键按作用域与账号隔离，避免跨账号读取他人响应
        IdempotencyRecord record = idempotencyRecordMapper.selectOne(Wrappers.<IdempotencyRecord>lambdaQuery()
                .eq(IdempotencyRecord::getScope, scope)
                .eq(IdempotencyRecord::getIdempotencyKey, idempotencyKey)
                .eq(IdempotencyRecord::getUserId, userId == null ? "" : userId).last("FOR UPDATE"));
        if (record == null) {
            return Optional.empty();
        }
        // 同一幂等键但请求内容不同时判定为冲突，要求客户端更换幂等键
        boolean conflict = !requestHash.equals(record.getRequestHash());
        if (conflict) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "幂等键已用于其他请求内容，请更换 Idempotency-Key");
        }
        return Optional.ofNullable(record.getResponseJson());
    }

    @Override
    public void saveResponse(String scope, String idempotencyKey, String userId, String requestHash,
                             String responseJson) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || responseJson == null) {
            return;
        }
        // 保存首次成功响应快照，供重试请求直接回放
        idempotencyRecordMapper.update(null, Wrappers.<IdempotencyRecord>lambdaUpdate()
                .eq(IdempotencyRecord::getScope, scope)
                .eq(IdempotencyRecord::getIdempotencyKey, idempotencyKey)
                .eq(IdempotencyRecord::getUserId, userId == null ? "" : userId)
                .set(IdempotencyRecord::getResponseJson, responseJson));
    }
}

package com.qaqxianyuqaq.backend.repository.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qaqxianyuqaq.backend.domain.entity.IdempotencyRecord;

/** 幂等记录持久化接口。 */
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {
}

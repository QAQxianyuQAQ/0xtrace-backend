package com.qaqxianyuqaq.backend.repository.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qaqxianyuqaq.backend.domain.entity.IdempotencyRecord;

/** 幂等记录持久化接口。 */
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {
    /** 在业务副作用之前认领；冲突时等待持有该行的事务完成，不覆盖请求摘要。 */
    @org.apache.ibatis.annotations.Insert("""
        INSERT INTO idempotency_records (id,scope,idempotency_key,user_id,request_hash,created_at)
        VALUES (#{id},#{scope},#{idempotencyKey},#{userId},#{requestHash},#{createdAt})
        ON DUPLICATE KEY UPDATE id=id
        """)
    void claim(IdempotencyRecord record);

}

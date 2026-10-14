package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 幂等记录实体：同一幂等键与请求摘要重放时直接返回首次响应快照。 */
@Getter
@Setter
@TableName("idempotency_records")
public class IdempotencyRecord {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 幂等作用域，例如 submission:CRY-02。 */
    private String scope;

    private String idempotencyKey;

    private String userId;

    /** 请求内容摘要，键相同但内容不同时返回冲突。 */
    private String requestHash;

    /** 首次成功响应的 JSON 快照。 */
    private String responseJson;

    private LocalDateTime createdAt;
}

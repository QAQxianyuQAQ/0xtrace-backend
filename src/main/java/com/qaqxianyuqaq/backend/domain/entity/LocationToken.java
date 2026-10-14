package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 现场位置凭证实体：仅存储令牌哈希，令牌明文只在签发时返回一次。 */
@Getter
@Setter
@TableName("location_tokens")
public class LocationToken {

    @TableId(type = IdType.INPUT)
    private String id;

    private String challengeId;

    /** 令牌原始值的 SHA-256 摘要。 */
    private String tokenHash;

    /** 轮换代数，重新生成时自增。 */
    private Integer generation;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}

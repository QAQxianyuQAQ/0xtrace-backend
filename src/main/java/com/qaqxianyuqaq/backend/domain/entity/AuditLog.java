package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 审计日志实体：记录后台操作者、动作、对象与请求 ID。 */
@Getter
@Setter
@TableName("audit_logs")
public class AuditLog {

    @TableId(type = IdType.INPUT)
    private String id;

    private String actorId;

    private String actorName;

    private String action;

    private String entityType;

    private String entityId;

    private String beforeJson;

    private String afterJson;

    private String requestId;

    private String clientIp;

    private LocalDateTime createdAt;
}

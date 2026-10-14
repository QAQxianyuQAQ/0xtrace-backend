package com.qaqxianyuqaq.backend.domain.vo.admin;

import java.time.LocalDateTime;

/** 审计日志响应。 */
public record AuditLogVO(String id, String actor, String actorId, String action, String entityType, String entityId,
                         String requestId, String clientIp, String before, String after, LocalDateTime createdAt) {
}

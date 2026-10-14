package com.qaqxianyuqaq.backend.domain.dto.audit;

/** 审计命令模型：由切面组装并交给审计服务落库。 */
public record AuditCommandDTO(String actorId, String actorName, String action, String entityType, String entityId,
                              String before, String after, String requestId, String clientIp) {
}

package com.qaqxianyuqaq.backend.domain.dto.admin;

import jakarta.validation.constraints.Size;

/** 完整审计查询仅供最高管理员使用。 */
public record AuditLogQueryDTO(@Size(max = 80) String action, @Size(max = 40) String entityType,
        @Size(max = 128) String actor, @Size(max = 40) String actorId, @Size(max = 40) String entityId,
        @Size(max = 128) String requestId, @Size(max = 40) String from, @Size(max = 40) String to) {
}

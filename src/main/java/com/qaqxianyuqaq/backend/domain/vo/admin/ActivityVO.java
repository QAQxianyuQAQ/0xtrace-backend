package com.qaqxianyuqaq.backend.domain.vo.admin;

import java.time.LocalDateTime;

/** 近期活动摘要。 */
public record ActivityVO(String id, String actor, String action, String entityType, String entityId,
                         LocalDateTime createdAt) {
}

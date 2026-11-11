package com.qaqxianyuqaq.backend.domain.vo.admin;

import java.time.LocalDateTime;

/** 可由最高管理员管理的普通管理员账号。 */
public record ManagedAccountVO(String id, String username, String role, String status,
                               LocalDateTime createdAt, LocalDateTime lastLoginAt) {
}

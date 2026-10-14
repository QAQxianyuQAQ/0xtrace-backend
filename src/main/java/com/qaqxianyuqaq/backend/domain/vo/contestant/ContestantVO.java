package com.qaqxianyuqaq.backend.domain.vo.contestant;

import java.time.LocalDateTime;

/** 账号响应，永不包含密码或密码哈希。 */
public record ContestantVO(String id, String username, TeamBriefVO team, String nickname, String note,
                           String status, LocalDateTime createdAt, LocalDateTime lastLoginAt) {
}

package com.qaqxianyuqaq.backend.domain.vo.event;

import java.time.LocalDateTime;

/** 赛事响应：公开与后台接口共用，后台不额外暴露敏感字段。 */
public record EventVO(String id, String name, String status, String timezone, LocalDateTime startsAt,
                      LocalDateTime endsAt, Integer maxTeamSize, LocalDateTime updatedAt) {
}

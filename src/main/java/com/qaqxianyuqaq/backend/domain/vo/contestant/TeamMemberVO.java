package com.qaqxianyuqaq.backend.domain.vo.contestant;

import java.time.LocalDateTime;

/** 后台队伍成员响应，字段足够定位成员及其队伍身份。 */
public record TeamMemberVO(String userId, String username, String nickname, boolean captain,
                           LocalDateTime joinedAt) {
}

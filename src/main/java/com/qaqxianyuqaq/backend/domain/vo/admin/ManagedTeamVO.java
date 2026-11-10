package com.qaqxianyuqaq.backend.domain.vo.admin;

import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMemberVO;

import java.time.LocalDateTime;
import java.util.List;

/** 后台赛事队伍及其成员信息。 */
public record ManagedTeamVO(String id, String eventId, String name, String note, int memberCount,
                            List<TeamMemberVO> members, LocalDateTime createdAt) {
}

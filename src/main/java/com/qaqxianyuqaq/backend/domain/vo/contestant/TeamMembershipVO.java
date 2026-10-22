package com.qaqxianyuqaq.backend.domain.vo.contestant;

/** 选手在当前赛事中的队伍信息，唯一标识码可供队友加入。 */
public record TeamMembershipVO(String id, String eventId, String name, String joinCode, boolean captain,
                               int memberCount, int maxMembers) {
}

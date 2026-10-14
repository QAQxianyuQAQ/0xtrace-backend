package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

import java.time.LocalDateTime;

/** 积分事件响应：题目得分或后台手工调整。 */
public record ScoreEventVO(String id, String type, String challengeCode, Integer basePoints, Integer bonusPoints,
                           Integer delta, String reason, String actor, LocalDateTime awardedAt) {
}

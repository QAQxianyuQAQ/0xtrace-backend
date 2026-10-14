package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

import java.time.LocalDateTime;

/** 榜单统计信息。 */
public record LeaderboardStatsVO(int teams, int contestants, int solvedChallenges, LocalDateTime endsAt) {
}

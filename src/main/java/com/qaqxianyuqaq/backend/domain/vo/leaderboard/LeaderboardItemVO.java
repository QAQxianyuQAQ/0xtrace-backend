package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

import java.time.LocalDateTime;

/** 榜单条目，isMe 仅在登录且命中当前主体时为 true。 */
public record LeaderboardItemVO(int rank, String teamId, String userId, String teamName, int solvedCount,
                                int score, boolean isMe, LocalDateTime lastSolvedAt) {
}

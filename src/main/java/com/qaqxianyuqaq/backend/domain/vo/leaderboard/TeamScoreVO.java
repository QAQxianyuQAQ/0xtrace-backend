package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

/** 队伍排名摘要。 */
public record TeamScoreVO(String id, String name, int rank, int score, int solvedCount) {
}

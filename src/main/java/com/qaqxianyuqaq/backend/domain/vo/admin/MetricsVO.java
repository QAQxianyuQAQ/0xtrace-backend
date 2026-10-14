package com.qaqxianyuqaq.backend.domain.vo.admin;

/** 总览指标。 */
public record MetricsVO(int challengeCount, int publishedChallengeCount, int contestantCount,
                        int activeContestantCount, int solvedCount, int submissionCount) {
}

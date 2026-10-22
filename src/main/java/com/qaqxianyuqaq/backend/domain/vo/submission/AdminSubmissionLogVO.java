package com.qaqxianyuqaq.backend.domain.vo.submission;

import java.time.LocalDateTime;

/** 管理端队伍提交日志，保留提交成员与题目判题结果之间的对应关系。 */
public record AdminSubmissionLogVO(String submissionId, String userId, String username, String challengeCode,
                                   String challengeTitle, String result, Integer pointsAwarded,
                                   Integer solvePlace, String source, LocalDateTime submittedAt) {
}

package com.qaqxianyuqaq.backend.domain.vo.submission;

import java.time.LocalDateTime;

/** 提交记录响应。 */
public record SubmissionVO(String submissionId, String challengeCode, String challengeTitle, String result,
                           Integer pointsAwarded, Integer solvePlace, String source, LocalDateTime submittedAt) {
}

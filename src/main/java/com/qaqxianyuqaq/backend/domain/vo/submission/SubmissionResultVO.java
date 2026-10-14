package com.qaqxianyuqaq.backend.domain.vo.submission;

import java.time.LocalDateTime;

/** 判题结果响应：不泄漏标准答案，重复提交返回首次完成数据。 */
public record SubmissionResultVO(String submissionId, String challengeCode, String result, String completion,
                                 LocalDateTime solvedAt, Integer basePoints, Integer bonusAwarded,
                                 Integer pointsAwarded, Integer solvePlace, ProgressVO progress, String source) {
}

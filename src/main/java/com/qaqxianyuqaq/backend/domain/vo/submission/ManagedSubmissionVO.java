package com.qaqxianyuqaq.backend.domain.vo.submission;

import java.time.LocalDateTime;

/** 后台判题记录：不返回答案、摘要或动态 Flag。 */
public record ManagedSubmissionVO(String submissionId, String eventId, String userId, String username,
        String nickname, String teamId, String teamName, String challengeCode, String challengeTitle,
        String result, Integer pointsAwarded, Integer solvePlace, String source, String requestId,
        LocalDateTime submittedAt) {
}

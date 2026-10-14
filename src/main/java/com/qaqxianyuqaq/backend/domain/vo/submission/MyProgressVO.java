package com.qaqxianyuqaq.backend.domain.vo.submission;

import java.util.List;

/** 个人/队伍进度响应：含累计分数与已解题目编号。 */
public record MyProgressVO(int solved, int total, int score, String teamId, String teamName,
                           List<String> solvedChallengeCodes) {
}

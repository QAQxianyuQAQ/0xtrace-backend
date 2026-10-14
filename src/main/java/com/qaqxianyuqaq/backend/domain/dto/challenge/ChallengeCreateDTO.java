package com.qaqxianyuqaq.backend.domain.dto.challenge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 创建题目请求，code 规则为 2-24 位大写英数及 _ -。 */
public record ChallengeCreateDTO(
        @Pattern(regexp = "^[A-Z0-9_-]{2,24}$", message = "题目编号需为 2-24 位大写字母、数字、_ 或 -")
        String code,
        @Size(max = 128, message = "题目标题过长") String title,
        @Size(max = 32, message = "分类长度过长") String category,
        @Size(max = 32, message = "难度长度过长") String difficulty,
        @Min(value = 1, message = "分值必须为正整数") Integer points,
        BonusesDTO bonuses,
        @Size(max = 8000, message = "题目描述过长") String description,
        @Size(max = 512, message = "答案长度过长") String answer,
        @Size(max = 512, message = "封面地址过长") String coverUrl,
        String status) {
}

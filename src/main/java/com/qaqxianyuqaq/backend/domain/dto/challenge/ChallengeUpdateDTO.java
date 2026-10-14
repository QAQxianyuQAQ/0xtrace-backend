package com.qaqxianyuqaq.backend.domain.dto.challenge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 编辑题目请求：未传字段表示保持不变，version 用于乐观锁校验。 */
public record ChallengeUpdateDTO(
        @Size(max = 128, message = "题目标题过长") String title,
        @Size(max = 32, message = "分类长度过长") String category,
        @Size(max = 32, message = "难度长度过长") String difficulty,
        @Min(value = 1, message = "分值必须为正整数") Integer points,
        BonusesDTO bonuses,
        @Size(max = 8000, message = "题目描述过长") String description,
        @Size(max = 512, message = "答案长度过长") String answer,
        @Size(max = 512, message = "封面地址过长") String coverUrl,
        String status,
        Integer version) {
}

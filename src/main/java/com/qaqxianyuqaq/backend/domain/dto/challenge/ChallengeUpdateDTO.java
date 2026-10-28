package com.qaqxianyuqaq.backend.domain.dto.challenge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 编辑题目请求：未传字段表示保持不变，version 用于乐观锁校验。 */
public record ChallengeUpdateDTO(
        @Size(max = 128, message = "题目标题过长") String title,
        @Size(max = 32, message = "分类长度过长") String category,
        @Size(max = 32, message = "难度长度过长") String difficulty,
        @Min(value = 1, message = "分值必须为正整数") Integer points,
        BonusesDTO bonuses,
        @Size(max = 8000, message = "题目描述过长") String description,
        @Size(max = 512, message = "Flag 长度过长") String answer,
        @Size(max = 255, message = "Docker 镜像地址过长")
        @Pattern(regexp = "^[a-z0-9][a-z0-9._/-]*$", message = "Docker 镜像地址格式不正确")
        String dockerImage,
        @Size(max = 128, message = "Docker 标签过长")
        @Pattern(regexp = "^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$", message = "Docker 标签格式不正确")
        String dockerTag,
        @Size(max = 128, message = "环境变量名过长")
        @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$", message = "环境变量名格式不正确")
        String flagEnvName,
        @Size(max = 32, message = "Flag 头过长")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "Flag 头只能包含字母、数字、_ 或 -")
        String flagPrefix,
        @Size(max = 512, message = "封面地址过长") String coverUrl,
        String status,
        Integer version) {
}

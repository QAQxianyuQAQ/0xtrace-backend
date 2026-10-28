package com.qaqxianyuqaq.backend.domain.vo.challenge;

import com.qaqxianyuqaq.backend.domain.dto.challenge.BonusesDTO;
import com.qaqxianyuqaq.backend.domain.vo.file.FileVO;

import java.time.LocalDateTime;
import java.util.List;

/** 管理端题目响应：包含答案等私有字段。 */
public record AdminChallengeVO(String id, String eventId, String code, String title, String category, String difficulty,
                               Integer points, BonusesDTO bonuses, String description, String answer,
                               String coverUrl, String status, Integer version, LocalDateTime publishedAt,
                               LocalDateTime createdAt, LocalDateTime updatedAt, List<FileVO> attachments,
                               boolean containerEnabled, String runtimeType, String dockerImage, String dockerTag,
                               String flagEnvName, String flagPrefix) {
}

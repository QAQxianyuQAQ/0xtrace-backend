package com.qaqxianyuqaq.backend.domain.vo.challenge;

import com.qaqxianyuqaq.backend.domain.vo.file.FileVO;

import java.util.List;

/**
 * 选手端题目缓存载荷。
 *
 * <p>刻意不包含 myStatus 等按人变动的字段，避免缓存串号；这些字段在响应组装时实时计算。</p>
 */
public record ChallengePayloadVO(String code, String title, String category, String difficulty, Integer points,
                                 String description, String coverUrl, List<FileVO> attachments,
                                 boolean containerEnabled) {
}

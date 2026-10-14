package com.qaqxianyuqaq.backend.domain.vo.challenge;

import com.qaqxianyuqaq.backend.domain.vo.file.FileVO;

import java.util.List;

/** 选手端题目响应：永不包含 answer 字段。 */
public record ChallengeVO(String code, String title, String category, String difficulty, Integer points,
                          String description, String coverUrl, List<FileVO> attachments, String myStatus) {
}

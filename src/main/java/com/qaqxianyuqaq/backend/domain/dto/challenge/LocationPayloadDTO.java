package com.qaqxianyuqaq.backend.domain.dto.challenge;

/** 现场二维码载荷解析结果：题目编号与令牌明文。 */
public record LocationPayloadDTO(String challengeCode, String token) {
}

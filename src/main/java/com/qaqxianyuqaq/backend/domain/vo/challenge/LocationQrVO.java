package com.qaqxianyuqaq.backend.domain.vo.challenge;

import java.time.LocalDateTime;

/** 位置二维码签发响应：payload 明文仅返回一次。 */
public record LocationQrVO(String challengeCode, String payload, String imageUrl, LocalDateTime createdAt,
                           LocalDateTime expiresAt, Integer generation) {
}

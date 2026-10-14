package com.qaqxianyuqaq.backend.domain.dto.challenge;

import java.time.LocalDateTime;

/** 位置二维码签发请求。 */
public record LocationQrRequestDTO(LocalDateTime expiresAt, Boolean rotate) {
}

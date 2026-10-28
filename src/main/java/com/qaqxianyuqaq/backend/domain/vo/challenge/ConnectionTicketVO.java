package com.qaqxianyuqaq.backend.domain.vo.challenge;

import java.time.LocalDateTime;

/** 一次性 WebSocket 连接票据，只能用于当前选手的当前队伍实例。 */
public record ConnectionTicketVO(String ticket, String wssUrl, LocalDateTime expiresAt) {
}

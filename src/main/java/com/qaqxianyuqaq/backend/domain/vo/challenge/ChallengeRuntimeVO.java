package com.qaqxianyuqaq.backend.domain.vo.challenge;

import java.time.LocalDateTime;

/** 队伍题目容器状态响应：不包含 Flag 明文和环境变量实际值。 */
public record ChallengeRuntimeVO(String instanceId, String challengeCode, String teamId, String status,
                                 String containerIp, String hostIp, Integer hostPort, Integer containerPort,
                                 String wssUrl, String lastError, LocalDateTime readyAt, LocalDateTime expiresAt,
                                 Long remainingSeconds) {
}

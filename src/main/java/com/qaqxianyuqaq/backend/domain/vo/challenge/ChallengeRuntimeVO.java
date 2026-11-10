package com.qaqxianyuqaq.backend.domain.vo.challenge;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.util.UriUtils;

/** 队伍题目容器状态响应：不包含 Flag 明文和环境变量实际值。 */
public record ChallengeRuntimeVO(String instanceId, String challengeCode, String teamId, String status,
                                 String containerIp, String hostIp, Integer hostPort, Integer containerPort,
                                 String wssUrl, String lastError, LocalDateTime readyAt, LocalDateTime expiresAt,
                                 Long remainingSeconds) {
    /** 仅为有效就绪实例返回同源鉴权入口，不把内网 IP 或宿主端口当成公网地址。 */
    @JsonProperty("entryPath")
    public String entryPath() {
        if (instanceId == null || instanceId.isBlank() || challengeCode == null || challengeCode.isBlank()
                || !"READY".equals(status) || remainingSeconds == null || remainingSeconds <= 0) {
            return null;
        }
        return "/entry/" + UriUtils.encodePathSegment(challengeCode, StandardCharsets.UTF_8) + "/";
    }
}

package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.config.RuntimeProperties;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ConnectionTicketVO;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/** WebSocket 连接票据：绑定用户、赛事队伍、题目和容器实例，消费后立即失效。 */
@Component
public class ConnectionTicketService {

    private final CacheService cacheService;

    private final RuntimeProperties runtime;

    public ConnectionTicketService(CacheService cacheService, TraceQuestProperties properties) {
        this.cacheService = cacheService;
        this.runtime = properties.getRuntime();
    }

    /** 签发短效票据并返回公网 WSS 地址。 */
    public ConnectionTicketVO issue(UserPrincipal principal, Challenge challenge, Team team,
                                    ChallengeContainerInstance instance) {
        String ticket = IdGenerator.next("ct");
        String value = String.join("|", principal.userId(), team.getId(), challenge.getId(), instance.getId());
        cacheService.set(CacheKeys.connectionTicket(ticket), value, runtime.getConnectionTicketTtl());
        LocalDateTime expiresAt = TimeUtil.now().plus(runtime.getConnectionTicketTtl());
        return new ConnectionTicketVO(ticket, runtime.getPublicWssUrl(), expiresAt);
    }

    /** 消费票据；重复使用或过期票据返回空。 */
    public Optional<Ticket> consume(String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            return Optional.empty();
        }
        String key = CacheKeys.connectionTicket(rawTicket);
        Optional<String> value = cacheService.take(key);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = value.get().split("\\|", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        return Optional.of(new Ticket(parts[0], parts[1], parts[2], parts[3]));
    }

    /** 票据内部载荷，不向客户端返回。 */
    public record Ticket(String userId, String teamId, String challengeId, String instanceId) {
    }
}

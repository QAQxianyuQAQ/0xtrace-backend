package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.security.ConnectionTicketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** WebSocket 握手鉴权：消费一次性票据并校验浏览器来源。 */
@Component
public class ChallengeConnectionHandshakeInterceptor implements HandshakeInterceptor {

    /** 票据在 WebSocket 会话属性中的键。 */
    public static final String TICKET_ATTRIBUTE = "tracequest.ticket";

    private final ConnectionTicketService ticketService;

    private final TraceQuestProperties properties;

    public ChallengeConnectionHandshakeInterceptor(ConnectionTicketService ticketService,
                                                   TraceQuestProperties properties) {
        this.ticketService = ticketService;
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!originAllowed(request)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("ticket");
        var consumed = ticketService.consume(ticket);
        if (consumed.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(TICKET_ATTRIBUTE, consumed.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手结束后无需额外处理
    }

    /** 校验 Origin 与握手 Host 同源，或命中显式白名单。 */
    private boolean originAllowed(ServerHttpRequest request) {
        if (!properties.getSecurity().isEnforceSameOrigin()) {
            return true;
        }
        List<String> originHeaders = request.getHeaders().get("Origin");
        if (originHeaders == null || originHeaders.isEmpty()) {
            return false;
        }
        String origin = originHeaders.getFirst();
        List<String> allowed = properties.getSecurity().getAllowedOrigins();
        if (allowed != null && allowed.stream().anyMatch(value -> value.equalsIgnoreCase(origin))) {
            return true;
        }
        try {
            URI parsed = URI.create(origin);
            URI requestUri = request.getURI();
            return parsed.getHost() != null && parsed.getHost().equalsIgnoreCase(requestUri.getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

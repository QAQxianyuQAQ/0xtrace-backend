package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.security.ConnectionTicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import java.net.URI;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 非同源握手不得消费本可有效的短效票据。 */
class ChallengeConnectionHandshakeInterceptorTest {
    private final ConnectionTicketService tickets = mock(ConnectionTicketService.class);
    private final ServerHttpRequest request = mock(ServerHttpRequest.class);
    private final ServerHttpResponse response = mock(ServerHttpResponse.class);

    @ParameterizedTest
    @ValueSource(strings = {"http://ctf.example:8080", "https://ctf.example:8081", "https://evil.example:8080"})
    void shouldRejectCrossOriginBeforeConsumingTicket(String origin) {
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin(origin);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("https://ctf.example:8080/api/v1/challenge-connections?ticket=synthetic"));
        TraceQuestProperties properties = new TraceQuestProperties();
        properties.getSecurity().setEnforceSameOrigin(true);
        var interceptor = new ChallengeConnectionHandshakeInterceptor(tickets, properties);
        assertThat(interceptor.beforeHandshake(request, response, null, new HashMap<>())).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        verifyNoInteractions(tickets);
    }

    @Test void shouldConsumeTicketOnlyAfterSameOriginCheck() {
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin("https://ctf.example:8080");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("https://ctf.example:8080/api/v1/challenge-connections?ticket=synthetic"));
        TraceQuestProperties properties = new TraceQuestProperties();
        properties.getSecurity().setEnforceSameOrigin(true);
        var interceptor = new ChallengeConnectionHandshakeInterceptor(tickets, properties);
        assertThat(interceptor.beforeHandshake(request, response, null, new HashMap<>())).isFalse();
        verify(tickets).consume("synthetic");
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}

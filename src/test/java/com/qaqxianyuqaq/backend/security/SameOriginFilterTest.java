package com.qaqxianyuqaq.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 同源过滤器单元测试：写请求必须携带与 Host 匹配的 Origin。 */
class SameOriginFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("缺失 Origin 的写请求被拒绝")
    void shouldRejectMutatingRequestWithoutOrigin() throws Exception {
        SameOriginFilter filter = filter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/challenges/GEO-01/submissions");
        request.addHeader("Host", "ctf.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> proceeded.set(true);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(proceeded).isFalse();
    }

    @Test
    @DisplayName("同源写请求放行，跨源写请求被拒绝")
    void shouldAllowSameOriginAndRejectCrossOrigin() throws Exception {
        SameOriginFilter filter = filter(true);
        MockHttpServletRequest allowed = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        allowed.setServerName("ctf.example.com");
        allowed.setServerPort(8080);
        allowed.addHeader("Host", "ctf.example.com:8080");
        allowed.addHeader("Origin", "http://ctf.example.com:8080");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        AtomicBoolean allowedProceeded = new AtomicBoolean(false);
        filter.doFilter(allowed, allowedResponse, (req, res) -> allowedProceeded.set(true));
        assertThat(allowedProceeded).isTrue();

        MockHttpServletRequest rejected = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        rejected.addHeader("Host", "ctf.example.com");
        rejected.addHeader("Origin", "http://evil.example.com");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(rejected, rejectedResponse, (req, res) -> {
            throw new IllegalStateException("跨源请求不应进入后续过滤器链");
        });
        assertThat(rejectedResponse.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("GET 请求不校验来源，便于榜单等公开读取")
    void shouldSkipCheckForReadRequests() throws Exception {
        SameOriginFilter filter = filter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/leaderboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> proceeded.set(true));

        assertThat(proceeded).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://ctf.example.com:8080", "https://ctf.example.com:8081",
            "https://ctf.example.com", "https://user@ctf.example.com:8080",
            "https://ctf.example.com:8080/path", "https://ctf.example.com:8080?query=1",
            "https://ctf.example.com:8080#fragment", "null"})
    void shouldRejectDifferentOrMalformedOrigins(String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setScheme("https");
        request.setServerName("ctf.example.com");
        request.setServerPort(8080);
        request.addHeader("Host", "ctf.example.com:8080");
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(true).doFilter(request, response, (req, res) -> {
            throw new AssertionError("跨源或畸形 Origin 不得放行");
        });
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://ctf.example.com", "https://ctf.example.com:443"})
    void shouldTreatDefaultTlsPortAsTheSameOrigin(String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setScheme("https");
        request.setServerName("ctf.example.com");
        request.setServerPort(443);
        request.addHeader("Host", "ctf.example.com");
        request.addHeader("Origin", origin);
        AtomicBoolean proceeded = new AtomicBoolean();
        filter(true).doFilter(request, new MockHttpServletResponse(), (req, res) -> proceeded.set(true));
        assertThat(proceeded).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://ctf.example.com:8081", "https://ctf.example.com:8082"})
    void shouldCompareTheOriginWithTrustedProxyMetadata(String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setScheme("http");
        request.setServerName("internal-backend");
        request.setServerPort(8080);
        request.addHeader("Host", "internal-backend:8080");
        request.addHeader("X-Forwarded-Host", "ctf.example.com:8081");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean proceeded = new AtomicBoolean();
        new ForwardedHeaderFilter().doFilter(request, response, (forwarded, res) ->
                filter(true).doFilter(forwarded, res, (req, result) -> proceeded.set(true)));
        boolean sameOrigin = origin.endsWith(":8081");
        assertThat(proceeded.get()).isEqualTo(sameOrigin);
        assertThat(response.getStatus()).isEqualTo(sameOrigin ? 200 : 403);
    }

    private SameOriginFilter filter(boolean enforce) {
        TraceQuestProperties properties = new TraceQuestProperties();
        properties.getSecurity().setEnforceSameOrigin(enforce);
        return new SameOriginFilter(properties, objectMapper);
    }
}

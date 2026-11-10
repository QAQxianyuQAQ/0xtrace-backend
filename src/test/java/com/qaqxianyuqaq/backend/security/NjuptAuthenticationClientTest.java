package com.qaqxianyuqaq.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.exception.BusinessException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.util.NjuptCredentialUtil;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class NjuptAuthenticationClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> tenant = new AtomicReference<>();
    private HttpServer server;
    private NjuptAuthenticationClient client;
    private int status = 200;
    private String response = "";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tenant.set(exchange.getRequestHeaders().getFirst("Tenant-Id"));
            exchange.getResponseHeaders().set("Location", "/must-not-follow");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client = new NjuptAuthenticationClient(mapper, new TraceQuestProperties(), new OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/login");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void shouldUseTheSuppliedAesVectorAndExactProtocol() throws Exception {
        response = "{\"success\":true,\"code\":200,\"result\":{\"token\":\"school-token-never-returned\",\"userInfo\":{\"username\":\"B25041114\"}}}";
        client.authenticate("B25041114", "synthetic-password");
        var body = mapper.readTree(received.get());
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.path("username").asText()).isEqualTo("d731f8de34f229e1ad49cde8eac4f8ac");
        assertThat(body.path("password").asText()).isEqualTo(NjuptCredentialUtil.encrypt("synthetic-password", "1629428467008"));
        assertThat(body.path("checkKey").asText()).isEqualTo("1629428467008");
        assertThat(body.path("captchaVerification").isNull()).isTrue();
        assertThat(body.path("appId").asText()).isEqualTo("common");
        assertThat(body.path("mode").asText()).isEqualTo("none");
        assertThat(tenant.get()).isEqualTo("0");
        assertThat(received.get()).doesNotContain("synthetic-password", "B25041114");
    }

    static Stream<Object[]> failures() {
        return Stream.of(
            new Object[]{401, "", ErrorCode.INVALID_CREDENTIALS},
            new Object[]{429, "", ErrorCode.RATE_LIMITED},
            new Object[]{500, "", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{302, "redirect", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "<html>upstream failed</html>", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "{}", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "null", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "{\"success\":true,\"code\":4294967496,\"result\":{\"token\":\"x\"}}", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "{\"codeType\":\"sz\"}", ErrorCode.NJUPT_CAPTCHA_REQUIRED},
            new Object[]{200, "{\"success\":false,\"code\":511}", ErrorCode.NJUPT_ACCOUNT_ACTION_REQUIRED},
            new Object[]{200, "{\"success\":false,\"code\":512}", ErrorCode.NJUPT_ACCOUNT_ACTION_REQUIRED},
            new Object[]{200, "{\"success\":false,\"code\":500}", ErrorCode.INVALID_CREDENTIALS},
            new Object[]{200, "{\"success\":\"true\",\"code\":200,\"result\":{\"token\":\"x\"}}", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "{\"success\":true,\"code\":\"200\",\"result\":{\"token\":\"x\"}}", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "{\"success\":true,\"code\":200,\"result\":{}}", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "{\"success\":true,\"code\":200,\"result\":{\"token\":\" \"}}", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, "{\"success\":true,\"code\":200,\"result\":{\"token\":\"x\",\"userInfo\":{\"username\":\"OTHER\"}}}", ErrorCode.NJUPT_AUTH_UNAVAILABLE},
            new Object[]{200, " ".repeat(65537), ErrorCode.NJUPT_AUTH_UNAVAILABLE}
        );
    }

    @ParameterizedTest
    @MethodSource("failures")
    void shouldFailClosedWithoutLeakingUpstreamContent(int httpStatus, String body, ErrorCode expected) {
        status = httpStatus;
        response = body;
        assertThatThrownBy(() -> client.authenticate("B25041114", "synthetic-password"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(expected);
                    assertThat(ex.getMessage()).doesNotContain("synthetic-password", "school-token-never-returned", "<html>");
                });
    }

    @Test
    void shouldReportConnectionFailureWithoutCredentials() {
        server.stop(0);
        assertThatThrownBy(() -> client.authenticate("B25041114", "synthetic-password"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NJUPT_AUTH_UNAVAILABLE));
    }
}

package com.qaqxianyuqaq.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 强制可重复读并发请求，防止校验入口重新包裹等待锁前的旧事务快照。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:scoring_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class ConcurrentSubmissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void shouldCommitOneSolveBeforeReleasingTheChallengeLock() throws Exception {
        Cookie admin = login("admin", "admin12345");
        mvc.perform(post("/api/v1/admin/contestants").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", "race_member", "password", "secret123", "teamName", "Race Team"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/challenges").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("code", "RACE-ONLY", "title", "并发回归", "category", "misc", "difficulty", "test",
                                "points", 100, "answer", "local-only-answer", "status", "draft"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/challenges/RACE-ONLY/publish").cookie(admin)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/event").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"open\"}"))
                .andExpect(status().isOk());
        Cookie player = login("race_member", "secret123");
        byte[] payload = json.writeValueAsBytes(Map.of("answer", "local-only-answer", "requestId", "race-request"));
        CountDownLatch start = new CountDownLatch(1);
        var responses = new HashSet<JsonNode>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Future<MvcResult>>();
            for (int i = 0; i < 6; i++) tasks.add(executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/challenges/RACE-ONLY/submissions").cookie(player)
                                .header("Idempotency-Key", "same-race-key").contentType(MediaType.APPLICATION_JSON).content(payload))
                        .andExpect(status().isOk()).andReturn();
            }));
            start.countDown();
            for (var task : tasks) responses.add(json.readTree(task.get(20, TimeUnit.SECONDS).getResponse().getContentAsByteArray()));
        }
        assertThat(responses).hasSize(1);
        assertThat(responses.iterator().next().path("pointsAwarded").asInt()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solves", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Integer.class)).isEqualTo(1);
        // 同键并发改题即使其中一个返回 409，也不得先让两道题都产生分数。
        int expectedSolves = 1;
        for (boolean headerKey : new boolean[]{true, false}) {
            String prefix = headerKey ? "RACE-HEADER" : "RACE-BODY";
            for (String code : new String[]{prefix + "-A", prefix + "-B"}) {
                mvc.perform(post("/api/v1/admin/challenges").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(Map.of("code", code, "title", code, "category", "misc",
                                        "difficulty", "test", "points", 100, "answer", "local-only-answer", "status", "draft"))))
                        .andExpect(status().isCreated());
                mvc.perform(post("/api/v1/admin/challenges/" + code + "/publish").cookie(admin)).andExpect(status().isOk());
            }
            var go = new CountDownLatch(1);
            var statuses = new ArrayList<Integer>();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var tasks = new ArrayList<Future<MvcResult>>();
                for (String code : new String[]{prefix + "-A", prefix + "-B"}) tasks.add(executor.submit(() -> {
                    go.await();
                    var builder = post("/api/v1/challenges/" + code + "/submissions").cookie(player)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(Map.of("answer", "local-only-answer", "requestId",
                                    headerKey ? code : prefix)));
                    if (headerKey) builder.header("Idempotency-Key", prefix);
                    return mvc.perform(builder).andReturn();
                }));
                go.countDown();
                for (var task : tasks) statuses.add(task.get(20, TimeUnit.SECONDS).getResponse().getStatus());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            expectedSolves++;
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM solves", Integer.class)).isEqualTo(expectedSolves);
        }

    }

    private Cookie login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String[] cookie = response.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2);
        return new Cookie(cookie[0], cookie[1]);
    }
}

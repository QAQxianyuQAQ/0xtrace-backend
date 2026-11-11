package com.qaqxianyuqaq.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 隔离数据库复现匿名读、错误方法、答案类型与已解题错误提交边界。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:security_hardening;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class SecurityHardeningIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    Cookie admin;

    @BeforeEach
    void authenticate() throws Exception {
        admin = login("admin", "admin12345");
    }

    @Test
    void businessReadsRequireLoginWhileHealthAndProvidersRemainPublic() throws Exception {
        for (String path : new String[]{"/event", "/challenges", "/challenges/SECURITY-UNKNOWN", "/leaderboard"}) {
            mvc.perform(get("/api/v1" + path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/providers")).andExpect(status().isOk());
        for (String path : new String[]{"/event", "/challenges", "/leaderboard"}) {
            mvc.perform(get("/api/v1" + path).cookie(admin)).andExpect(status().isOk());
        }
    }

    @Test
    void wrongMethodsReturn405WithAllowAndUnknownResourcesStay404() throws Exception {
        for (String code : new String[]{"SECURITY-UNKNOWN", createChallenge()}) {
            for (var method : new org.springframework.http.HttpMethod[]{org.springframework.http.HttpMethod.PATCH, org.springframework.http.HttpMethod.PUT}) {
                mvc.perform(request(method, "/api/v1/challenges/" + code).cookie(admin))
                        .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
                        .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
            }
            mvc.perform(get("/api/v1/challenges/" + code + "/submissions").cookie(admin))
                    .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"));
        }
        mvc.perform(get("/api/v1/challenges/SECURITY-UNKNOWN").cookie(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/no-such-resource").cookie(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/challenges").cookie(admin).param("page", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answerMustBeANonemptyJsonString() throws Exception {
        String code = createChallenge();
        for (String value : new String[]{"123", "1.25", "true", "[]", "{}", "null", "\"\"", "\"   \""}) {
            mvc.perform(post("/api/v1/challenges/" + code + "/submissions").cookie(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":" + value + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(post("/api/v1/challenges/" + code + "/submissions").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("answer", "x".repeat(513)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongAnswerAfterSolveIsIncorrectAndNeverChangesTheOriginalAward() throws Exception {
        String code = createChallenge();
        String username = "sec_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/admin/contestants").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password, "teamName", username))))
                .andExpect(status().isCreated());
        Cookie player = login(username, password);
        var first = json.readTree(mvc.perform(post("/api/v1/challenges/" + code + "/submissions").cookie(player)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"local-answer\",\"requestId\":\"first\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("correct"))
                .andReturn().getResponse().getContentAsString());
        for (int replay = 0; replay < 2; replay++) {
            mvc.perform(post("/api/v1/challenges/" + code + "/submissions").cookie(player)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"wrong-after-solve\",\"requestId\":\"wrong\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("incorrect"))
                    .andExpect(jsonPath("$.pointsAwarded").value(0));
        }
        var repeated = json.readTree(mvc.perform(post("/api/v1/challenges/" + code + "/submissions").cookie(player)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"local-answer\",\"requestId\":\"correct-again\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completion").value("already_solved"))
                .andReturn().getResponse().getContentAsString());
        assertThat(repeated.get("submissionId")).isEqualTo(first.get("submissionId"));
        mvc.perform(get("/api/v1/me/submissions").cookie(player)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total").value(2));
        mvc.perform(get("/api/v1/me/submissions").cookie(player).param("result", "incorrect"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").value(1));
        mvc.perform(get("/api/v1/me/progress").cookie(player)).andExpect(status().isOk())
                .andExpect(jsonPath("$.solved").value(1)).andExpect(jsonPath("$.score").value(first.get("pointsAwarded").asInt()));
    }

    @Test
    void adminStaticAccessCheckIsCheapAndRoleProtected() throws Exception {
        mvc.perform(get("/api/v1/auth/admin-access")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/admin-access").cookie(admin)).andExpect(status().isNoContent());
        String username = "access_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/admin/contestants").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/auth/admin-access").cookie(login(username, password))).andExpect(status().isForbidden());
    }

    private String createChallenge() throws Exception {
        String code = "SEC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        mvc.perform(post("/api/v1/admin/challenges").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", code, "title", code, "category", "misc", "difficulty", "test", "points", 100, "answer", "local-answer", "status", "draft"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/challenges/" + code + "/publish").cookie(admin)).andExpect(status().isOk());
        return code;
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("tq_session");
    }
}

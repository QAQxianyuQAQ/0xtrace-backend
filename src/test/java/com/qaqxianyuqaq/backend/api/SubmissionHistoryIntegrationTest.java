package com.qaqxianyuqaq.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 提交记录的空结果、分页和账号隔离回归。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:submission_history;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class SubmissionHistoryIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("空提交与筛选空结果正常返回，空页保留总数且不泄露其他队伍记录")
    void shouldReadEmptyAndPopulatedHistory() throws Exception {
        Cookie admin = login("admin", "admin12345");
        mockMvc.perform(post("/api/v1/admin/challenges").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "HISTORY-01", "title", "历史记录回归", "category", "图寻",
                                "difficulty", "中等", "points", 400, "bonuses", Map.of("first", 100, "second", 60, "third", 30, "other", 0),
                                "description", "回归测试", "answer", "flag{history}", "status", "draft"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/challenges/HISTORY-01/publish").cookie(admin))
                .andExpect(status().isOk());
        for (String username : new String[]{"history_player", "history_outsider"}) {
            mockMvc.perform(post("/api/v1/admin/contestants").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "secret123", "teamName", username))))
                    .andExpect(status().isCreated());
        }
        Cookie player = login("history_player", "secret123");
        mockMvc.perform(get("/api/v1/me/submissions").cookie(player))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.pagination.total").value(0));
        mockMvc.perform(get("/api/v1/me/submissions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/challenges/HISTORY-01/submissions").cookie(player).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answer", "flag{wrong}"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pointsAwarded").value(0));
        mockMvc.perform(get("/api/v1/me/submissions").cookie(player).param("challengeCode", "HISTORY-01").param("result", "correct"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.pagination.total").value(0));
        mockMvc.perform(post("/api/v1/challenges/HISTORY-01/submissions").cookie(player).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answer", "flag{history}"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pointsAwarded").value(500));
        mockMvc.perform(get("/api/v1/me/submissions").cookie(player).param("challengeCode", "HISTORY-01").param("result", "correct"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].challengeCode").value("HISTORY-01"))
                .andExpect(jsonPath("$.items[0].pointsAwarded").value(500))
                .andExpect(jsonPath("$.pagination.total").value(1));
        mockMvc.perform(get("/api/v1/me/submissions").cookie(player).param("challengeCode", "HISTORY-01")
                        .param("page", "2").param("pageSize", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.pagination.page").value(2))
                .andExpect(jsonPath("$.pagination.total").value(2))
                .andExpect(jsonPath("$.pagination.totalPages").value(1));
        Cookie outsider = login("history_outsider", "secret123");
        mockMvc.perform(get("/api/v1/me/submissions").cookie(outsider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.pagination.total").value(0));
    }

    private Cookie login(String username, String password) throws Exception {
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("tq_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}

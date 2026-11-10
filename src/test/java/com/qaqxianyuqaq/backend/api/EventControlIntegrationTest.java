package com.qaqxianyuqaq.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
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

/** 比赛状态管理的权限、完整流转及设置保留回归。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:event_control;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class EventControlIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("仅管理员可开始、暂停、恢复和结束比赛，状态变更保留预设时间")
    void shouldControlEventLifecycle() throws Exception {
        Cookie admin = login("admin", "admin12345");
        String created = mockMvc.perform(post("/api/v1/admin/event").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "State Control Test", "timezone", "Asia/Shanghai",
                                "maxTeamSize", 4, "startsAt", "2026-11-01T00:00:00Z", "endsAt", "2026-12-01T00:00:00Z"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("setup"))
                .andReturn().getResponse().getContentAsString();
        JsonNode before = objectMapper.readTree(created);
        mockMvc.perform(post("/api/v1/admin/contestants").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "state_player", "password", "secret123"))))
                .andExpect(status().isCreated());
        Cookie player = login("state_player", "secret123");
        mockMvc.perform(patch("/api/v1/admin/event").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/admin/event").cookie(player).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/admin/event").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"paused\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVENT_STATE_CONFLICT"));
        for (String target : new String[]{"open", "paused", "open", "closed"}) {
            String response = mockMvc.perform(patch("/api/v1/admin/event").cookie(admin)
                            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("status", target))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(target))
                    .andReturn().getResponse().getContentAsString();
            JsonNode after = objectMapper.readTree(response);
            for (String field : new String[]{"id", "name", "timezone", "maxTeamSize", "startsAt", "endsAt"}) {
                assertThat(after.get(field)).as(field + " should be preserved").isEqualTo(before.get(field));
            }
            mockMvc.perform(get("/api/v1/event"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(target));
        }
        mockMvc.perform(patch("/api/v1/admin/event").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVENT_STATE_CONFLICT"));
        mockMvc.perform(get("/api/v1/admin/event").cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("closed"));
    }

    private Cookie login(String username, String password) throws Exception {
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("tq_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}

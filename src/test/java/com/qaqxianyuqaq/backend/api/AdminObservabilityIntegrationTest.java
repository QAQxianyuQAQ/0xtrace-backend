package com.qaqxianyuqaq.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.domain.entity.AuditLog;
import com.qaqxianyuqaq.backend.repository.audit.AuditLogMapper;
import com.qaqxianyuqaq.backend.service.audit.AuditService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 管理信息端到端回归：真实 H2 事务、认证、分页、脱敏和队伍变更。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class AdminObservabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuditLogMapper auditMapper;
    @MockitoSpyBean AuditService auditService;

    private String suffix() { return Long.toUnsignedString(System.nanoTime(), 36); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private Cookie login(String name, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return new Cookie("tq_session", result.getResponse().getCookie("tq_session").getValue());
    }
    private Cookie root() throws Exception { return login("admin", "admin12345"); }
    private JsonNode account(Cookie root, String name, String team) throws Exception {
        return body(mvc.perform(post("/api/v1/admin/contestants").cookie(root).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "password", "secret123", "teamName", team))))
                .andExpect(status().isCreated()).andReturn());
    }
    private String event(Cookie root) throws Exception {
        return body(mvc.perform(get("/api/v1/event").cookie(root)).andReturn()).path("id").asText();
    }
    private String challenge(Cookie root) throws Exception {
        String code = "OBS-" + suffix().toUpperCase(java.util.Locale.ROOT);
        mvc.perform(post("/api/v1/admin/challenges").cookie(root).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code, "title", "Observation test", "category", "web",
                        "difficulty", "test", "points", 100, "bonuses", Map.of("first", 30, "second", 20, "third", 10, "other", 0),
                        "description", "synthetic", "answer", "flag{observability-private}"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/challenges/" + code + "/publish").cookie(root)).andExpect(status().isOk());
        return code;
    }
    private void submit(Cookie player, String code, String answer, String key) throws Exception {
        mvc.perform(post("/api/v1/challenges/" + code + "/submissions").cookie(player).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("answer", answer, "requestId", key))))
                .andExpect(status().isOk());
    }
    private JsonNode audit(Cookie root, String team, String action) throws Exception {
        return body(mvc.perform(get("/api/v1/admin/audit-log").cookie(root).param("entityType", "team")
                .param("entityId", team).param("action", action)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void shouldListFilterAndPaginateAllJudgementsWithoutAnswers() throws Exception {
        Cookie root = root(); String name="obs_"+suffix();
        JsonNode user=account(root,name,"Obs "+suffix()); Cookie player=login(name,"secret123");
        String code=challenge(root), id=user.path("id").asText(), team=user.path("team").path("id").asText();
        submit(player,code,"wrong","obs-wrong");
        submit(player,code,"flag{observability-private}","obs-right");
        submit(player,code,"flag{observability-private}","obs-repeat");
        JsonNode all=body(mvc.perform(get("/api/v1/admin/submissions").cookie(root)
                        .param("userId",id).param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").value(2)).andReturn());
        assertThat(all.path("items").size()).isEqualTo(1);
        JsonNode row=all.path("items").get(0);
        assertThat(row.path("teamId").asText()).isEqualTo(team);
        assertThat(row.path("username").asText()).isEqualTo(name);
        assertThat(row.has("answer")).isFalse(); assertThat(row.has("answerDigest")).isFalse();
        assertThat(all.toString()).doesNotContain("observability-private");
        mvc.perform(get("/api/v1/admin/submissions").cookie(root).param("userId",id)
                        .param("challengeCode",code).param("result","incorrect").param("source","answer")
                        .param("requestId","obs-wrong"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").value(1))
                .andExpect(jsonPath("$.items[0].pointsAwarded").value(0));
        mvc.perform(get("/api/v1/admin/submissions").cookie(root).param("userId",id).param("page","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.pagination.total").value(2));
        mvc.perform(get("/api/v1/admin/submissions").cookie(root).param("userId",id).param("from","2099-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").value(0));
        mvc.perform(get("/api/v1/admin/submissions").cookie(root).param("challengeCode","' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").value(0));
    }

    @Test
    void shouldEnforceRolesAndNotLeakFullAuditThroughDashboard() throws Exception {
        Cookie root=root(); String name="observer_"+suffix(); JsonNode user=account(root,name,"Observer "+suffix());
        Cookie contestant=login(name,"secret123");
        for(String path:new String[]{"/api/v1/admin/submissions","/api/v1/admin/audit-log"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).cookie(contestant)).andExpect(status().isForbidden());
        }
        mvc.perform(patch("/api/v1/admin/accounts/"+user.path("id").asText()+"/role").cookie(root)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk());
        Cookie ordinary=login(name,"secret123");
        mvc.perform(get("/api/v1/admin/submissions").cookie(ordinary)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit-log").cookie(ordinary)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/dashboard").cookie(ordinary)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recentActivity.length()").value(0));
    }

    @Test
    void shouldRejectInvalidFiltersAndCrossEventTeamIds() throws Exception {
        Cookie root=root(); JsonNode user=account(root,"scope_"+suffix(),"Scope "+suffix());
        String oldEvent=event(root), team=user.path("team").path("id").asText();
        for(String[] pair:new String[][]{{"result","anything"},{"source","anything"},{"from","not-a-time"}}) {
            mvc.perform(get("/api/v1/admin/submissions").cookie(root).param(pair[0],pair[1])).andExpect(status().isBadRequest());
        }
        for(String path:new String[]{"/api/v1/admin/submissions","/api/v1/admin/audit-log"}) {
            mvc.perform(get(path).cookie(root).param("from","2026-11-12T00:00:00Z").param("to","2026-11-11T00:00:00Z"))
                    .andExpect(status().isBadRequest());
        }
        JsonNode other=body(mvc.perform(post("/api/v1/admin/event").cookie(root).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other observation event\",\"maxTeamSize\":3}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(other.path("id").asText()).isNotEqualTo(oldEvent);
        mvc.perform(get("/api/v1/admin/submissions").cookie(root).param("eventId",other.path("id").asText()).param("teamId",team))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldKeepHistoricalTeamAndRecordBothSidesOfManagedMoves() throws Exception {
        Cookie root=root(); String name="mover_"+suffix(); JsonNode user=account(root,name,"Before "+suffix());
        JsonNode other=account(root,"owner_"+suffix(),"After "+suffix());
        String userId=user.path("id").asText(), old=user.path("team").path("id").asText(), next=other.path("team").path("id").asText();
        String code=challenge(root); submit(login(name,"secret123"),code,"wrong","before-team-move");
        MvcResult moved=mvc.perform(patch("/api/v1/admin/contestants/"+userId).cookie(root).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("teamId",next)))).andExpect(status().isOk()).andReturn();
        String request=moved.getResponse().getHeader("X-Request-Id");
        for(String team:new String[]{old,next}) {
            JsonNode logs=audit(root,team,"team.member.assigned");
            assertThat(logs.path("items").toString()).contains(old,next,userId);
        }
        JsonNode log=body(mvc.perform(get("/api/v1/admin/audit-log").cookie(root).param("requestId",request)
                .param("action","team.member.assigned")).andExpect(status().isOk()).andReturn());
        assertThat(log.path("pagination").path("total").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/submissions").cookie(root).param("teamId",old).param("userId",userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").value(1));
        mvc.perform(get("/api/v1/admin/submissions").cookie(root).param("teamId",next).param("userId",userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").value(0));
    }

    @Test
    void shouldRecordActualCreateJoinLeaveAndRenameWithoutInvitationCode() throws Exception {
        Cookie root=root(); String a="join_a_"+suffix(), b="join_b_"+suffix();
        account(root,a,""); account(root,b,""); Cookie player=login(a,"secret123"), teammate=login(b,"secret123");
        String event=event(root);
        JsonNode team=body(mvc.perform(post("/api/v1/events/"+event+"/teams").cookie(player).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name","Joined "+suffix())))).andExpect(status().isCreated()).andReturn());
        String id=team.path("id").asText(), invitation=team.path("joinCode").asText();
        mvc.perform(post("/api/v1/events/"+event+"/teams/join").cookie(teammate).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code",invitation)))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/events/"+event+"/teams/me").cookie(teammate)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/events/"+event+"/teams/me").cookie(teammate)).andExpect(status().isNoContent());
        for(String action:new String[]{"team.created","team.joined","team.left"}) {
            JsonNode rows=audit(root,id,action);
            assertThat(rows.path("pagination").path("total").asInt()).isEqualTo(1);
            assertThat(rows.path("items").get(0).path("entityId").asText()).isEqualTo(id);
            assertThat(rows.toString()).doesNotContain(invitation,"joinCode");
        }
        mvc.perform(patch("/api/v1/admin/teams/"+id).cookie(root).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed observation team\"}")).andExpect(status().isOk());
        JsonNode rename=audit(root,id,"team.renamed").path("items").get(0);
        assertThat(rename.path("before").asText()).contains(team.path("name").asText());
        assertThat(rename.path("after").asText()).contains("Renamed observation team");
    }

    @Test
    void shouldSanitizeHistoricalSnapshotsAndNewChallengeAudit() throws Exception {
        Cookie root=root(); String code=challenge(root);
        JsonNode logs=body(mvc.perform(get("/api/v1/admin/audit-log").cookie(root).param("entityId",code))
                .andExpect(status().isOk()).andReturn());
        assertThat(logs.path("items").size()).isGreaterThan(0);
        assertThat(logs.toString()).doesNotContain("observability-private","answer");
        AuditLog old=new AuditLog();old.setId("audit_"+suffix());old.setAction("legacy.snapshot");old.setEntityType("team");
        old.setBeforeJson("{\"name\":\"retained\",\"password\":\"do-not-expose\",\"team\":{\"joinCode\":\"private-code\"}}");
        old.setAfterJson("not-json-secret");auditMapper.insert(old);
        JsonNode row=body(mvc.perform(get("/api/v1/admin/audit-log").cookie(root).param("action","legacy.snapshot"))
                .andExpect(status().isOk()).andReturn()).path("items").get(0);
        assertThat(row.path("before").asText()).contains("retained").doesNotContain("private-code","do-not-expose","password");
        assertThat(row.hasNonNull("after")).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackTeamRenameIfAuditCannotBeSaved() throws Exception {
        Cookie root=root(); JsonNode user=account(root,"rollback_"+suffix(),"Rollback "+suffix());
        String team=user.path("team").path("id").asText(), name=user.path("team").path("name").asText();
        doThrow(new IllegalStateException("synthetic audit unavailable")).when(auditService)
                .recordTeamChange(eq("team.renamed"),eq(team),anyString(),anyString());
        try {
            mvc.perform(patch("/api/v1/admin/teams/"+team).cookie(root).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Must not persist\"}")).andExpect(status().isInternalServerError());
            JsonNode rows=body(mvc.perform(get("/api/v1/admin/teams").cookie(root).param("q",name))
                    .andExpect(status().isOk()).andReturn());
            assertThat(rows.path("items").get(0).path("name").asText()).isEqualTo(name);
        } finally { reset(auditService); }
    }
}

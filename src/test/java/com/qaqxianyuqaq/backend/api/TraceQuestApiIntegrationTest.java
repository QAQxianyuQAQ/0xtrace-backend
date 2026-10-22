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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端接口测试。
 *
 * <p>覆盖第 8 章契约中的认证、题目管理、判题计分、榜单、位置核验、审计与权限边界。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class TraceQuestApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("管理员建题、选手答题、榜单与权限边界的完整流程")
    void shouldRunFullContestFlow() throws Exception {
        String adminSession = login("admin", "admin12345");

        // 1. 创建带队伍的选手账号
        mockMvc.perform(post("/api/v1/admin/contestants")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "field_129", "password", "secret123",
                                "teamName", "North Signal", "note", "现场选手"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("field_129"))
                .andExpect(jsonPath("$.team.name").value("North Signal"))
                .andExpect(jsonPath("$.status").value("active"));

        // 2. 创建题目并发布
        mockMvc.perform(post("/api/v1/admin/challenges")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(challengePayload("GEO-01", "折射场", "图寻", 400))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("GEO-01"))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.answer").value("flag{refraction}"));

        mockMvc.perform(post("/api/v1/admin/challenges/GEO-01/publish")
                        .cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));

        // 3. 重复编号应返回 409
        mockMvc.perform(post("/api/v1/admin/challenges")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(challengePayload("GEO-01", "重复题目", "图寻", 100))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CODE_DUPLICATED"));

        String contestantSession = login("field_129", "secret123");

        // 4. 选手端题目列表不返回答案字段
        MvcResult listResult = mockMvc.perform(get("/api/v1/challenges").cookie(sessionCookie(contestantSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code == 'GEO-01')].myStatus").value(hasItem("unsolved")))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andReturn();
        assertThat(listResult.getResponse().getContentAsString()).doesNotContain("answer");

        // 5. 错误答案不产生积分
        mockMvc.perform(post("/api/v1/challenges/GEO-01/submissions")
                        .cookie(sessionCookie(contestantSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "flag{wrong}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("incorrect"))
                .andExpect(jsonPath("$.pointsAwarded").value(0));

        // 6. 正确答案为首解并计入 400 + 100 分
        MvcResult firstSolve = mockMvc.perform(post("/api/v1/challenges/GEO-01/submissions")
                        .cookie(sessionCookie(contestantSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "flag{refraction}", "requestId", "req-first"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("correct"))
                .andExpect(jsonPath("$.completion").value("first_solve"))
                .andExpect(jsonPath("$.basePoints").value(400))
                .andExpect(jsonPath("$.bonusAwarded").value(100))
                .andExpect(jsonPath("$.pointsAwarded").value(500))
                .andExpect(jsonPath("$.solvePlace").value(1))
                // 时间字段统一序列化为 UTC ISO-8601（以 Z 结尾）
                .andExpect(jsonPath("$.solvedAt").value(endsWith("Z")))
                .andReturn();
        String submissionId = read(firstSolve, "$.submissionId");

        // 7. 同一 requestId 重放返回既有结果且不再计分
        mockMvc.perform(post("/api/v1/challenges/GEO-01/submissions")
                        .cookie(sessionCookie(contestantSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "flag{refraction}", "requestId", "req-first"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completion").value("already_solved"))
                .andExpect(jsonPath("$.submissionId").value(submissionId))
                .andExpect(jsonPath("$.pointsAwarded").value(500));

        // 8. 进度与榜单反映权威分数
        mockMvc.perform(get("/api/v1/me/progress").cookie(sessionCookie(contestantSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solved").value(1))
                .andExpect(jsonPath("$.score").value(500))
                .andExpect(jsonPath("$.teamName").value("North Signal"));

        mockMvc.perform(get("/api/v1/leaderboard").param("scope", "team")
                        .cookie(sessionCookie(contestantSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.teamName == 'North Signal')].score").value(hasItem(500)))
                .andExpect(jsonPath("$.items[?(@.teamName == 'North Signal')].isMe").value(hasItem(true)));

        // 9. 后台总览与审计日志
        mockMvc.perform(get("/api/v1/admin/dashboard").cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.publishedChallengeCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.metrics.activeContestantCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.metrics.submissionCount").value(greaterThanOrEqualTo(2)));

        mockMvc.perform(get("/api/v1/admin/audit-log").cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").exists())
                .andExpect(jsonPath("$.pagination.total").value(greaterThanOrEqualTo(4)));

        // 10. 未登录与越权访问
        mockMvc.perform(get("/api/v1/me/progress"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/admin/dashboard").cookie(sessionCookie(contestantSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("幂等键重放、位置二维码核验与撤销流程")
    void shouldHandleIdempotencyAndLocationVerification() throws Exception {
        String adminSession = login("admin", "admin12345");

        // 1. 准备两名选手与一道已发布图寻题
        createContestant(adminSession, "field_130", "South Signal");
        createContestant(adminSession, "field_131", "East Signal");
        mockMvc.perform(post("/api/v1/admin/challenges")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(challengePayload("GEO-02", "台风眼", "图寻", 300))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/challenges/GEO-02/publish").cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk());

        // 2. 签发位置二维码，明文 payload 仅返回一次
        String payload = read(mockMvc.perform(post("/api/v1/admin/challenges/GEO-02/location-qr")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rotate", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.challengeCode").value("GEO-02"))
                .andExpect(jsonPath("$.generation").value(1))
                .andReturn(), "$.payload");
        assertThat(payload).startsWith("TRACEQUEST|LOCATION|GEO-02|");

        // 3. 首个核验队伍通过并获得首解加分（100）
        String southSession = login("field_130", "secret123");
        mockMvc.perform(post("/api/v1/location-verifications")
                        .cookie(sessionCookie(southSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("payload", payload, "requestId", "loc-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("correct"))
                .andExpect(jsonPath("$.completion").value("first_solve"))
                .andExpect(jsonPath("$.pointsAwarded").value(400))
                .andExpect(jsonPath("$.source").value("location"));

        // 4. 撤销凭证后核验失败
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/admin/challenges/GEO-02/location-qr")
                        .cookie(sessionCookie(adminSession)))
                .andExpect(status().isNoContent());

        String eastSession = login("field_131", "secret123");
        mockMvc.perform(post("/api/v1/location-verifications")
                        .cookie(sessionCookie(eastSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("payload", payload, "requestId", "loc-2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // 5. 幂等键重放返回首次响应，不重复计分
        MvcResult first = mockMvc.perform(post("/api/v1/challenges/GEO-02/submissions")
                        .cookie(sessionCookie(eastSession))
                        .header("Idempotency-Key", "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "TRACEQUEST|LOCATION|GEO-02|unused",
                                "requestId", "answer-1"))))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/v1/challenges/GEO-02/submissions")
                        .cookie(sessionCookie(eastSession))
                        .header("Idempotency-Key", "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "TRACEQUEST|LOCATION|GEO-02|unused",
                                "requestId", "answer-1"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(replay, "$.submissionId")).isEqualTo(read(first, "$.submissionId"));

        // 6. 同一幂等键换内容应返回 409
        mockMvc.perform(post("/api/v1/challenges/GEO-02/submissions")
                        .cookie(sessionCookie(eastSession))
                        .header("Idempotency-Key", "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "另一个答案", "requestId", "answer-2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @Transactional
    @DisplayName("赛事内创建/加入队伍、人数上限与成员提交日志")
    void shouldManageEventScopedTeamsAndSubmissionLogs() throws Exception {
        String adminSession = login("admin", "admin12345");

        // 1. 创建赛事时必须指定队伍人数上限
        MvcResult createdEvent = mockMvc.perform(post("/api/v1/admin/event")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Team Cup",
                                "timezone", "Asia/Shanghai",
                                "startsAt", "2026-10-22T09:00:00+08:00",
                                "endsAt", "2026-10-22T18:00:00+08:00",
                                "maxTeamSize", 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Team Cup"))
                .andExpect(jsonPath("$.status").value("setup"))
                .andExpect(jsonPath("$.maxTeamSize").value(2))
                .andReturn();
        String eventId = read(createdEvent, "$.id");

        // 2. 创建三名无队伍选手，前两名将加入同一队伍
        for (String username : new String[]{"team_captain", "team_member", "team_outsider"}) {
            mockMvc.perform(post("/api/v1/admin/contestants")
                            .cookie(sessionCookie(adminSession))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", username, "password", "secret123"))))
                    .andExpect(status().isCreated());
        }
        String captainSession = login("team_captain", "secret123");
        String memberSession = login("team_member", "secret123");
        String outsiderSession = login("team_outsider", "secret123");

        // 3. 队长创建队伍并获得唯一标识码
        MvcResult createdTeam = mockMvc.perform(post("/api/v1/events/" + eventId + "/teams")
                        .cookie(sessionCookie(captainSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Team Alpha"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.captain").value(true))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.maxMembers").value(2))
                .andReturn();
        String teamId = read(createdTeam, "$.id");
        String joinCode = read(createdTeam, "$.joinCode");

        // 4. 第二名选手用标识码加入，第三名因人数上限被拒绝
        mockMvc.perform(post("/api/v1/events/" + eventId + "/teams/join")
                        .cookie(sessionCookie(memberSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", joinCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(teamId))
                .andExpect(jsonPath("$.captain").value(false))
                .andExpect(jsonPath("$.memberCount").value(2));
        mockMvc.perform(post("/api/v1/events/" + eventId + "/teams/join")
                        .cookie(sessionCookie(outsiderSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", joinCode))))
                .andExpect(status().isConflict());

        // 5. 开放赛事后由队伍成员提交并确认按队伍计分
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/admin/event")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "open"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/challenges")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(challengePayload("TEAM-01", "队伍协作题", "CTF", 300))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/challenges/TEAM-01/publish")
                        .cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/challenges/TEAM-01/submissions")
                        .cookie(sessionCookie(memberSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answer", "flag{refraction}", "requestId", "team-solve-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("correct"))
                .andExpect(jsonPath("$.pointsAwarded").value(400));

        mockMvc.perform(get("/api/v1/leaderboard")
                        .param("scope", "team")
                        .param("eventId", eventId)
                        .cookie(sessionCookie(memberSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.teamName == 'Team Alpha')].score").value(hasItem(400)))
                .andExpect(jsonPath("$.items[?(@.teamName == 'Team Alpha')].isMe").value(hasItem(true)));

        // 6. 后台队伍详情返回全部成员及对应提交日志
        mockMvc.perform(get("/api/v1/admin/leaderboard/" + teamId)
                        .param("eventId", eventId)
                        .cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.name").value("Team Alpha"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[*].username").value(hasItem("team_captain")))
                .andExpect(jsonPath("$.members[*].username").value(hasItem("team_member")))
                .andExpect(jsonPath("$.submissionLogs.items[0].username").value("team_member"))
                .andExpect(jsonPath("$.submissionLogs.items[0].challengeCode").value("TEAM-01"));

        // 7. 同一选手在另一赛事中可以创建完全独立的队伍
        MvcResult otherEvent = mockMvc.perform(post("/api/v1/admin/event")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Team Cup 2",
                                "timezone", "Asia/Shanghai", "maxTeamSize", 3))))
                .andExpect(status().isCreated())
                .andReturn();
        String otherEventId = read(otherEvent, "$.id");
        mockMvc.perform(post("/api/v1/events/" + otherEventId + "/teams")
                        .cookie(sessionCookie(captainSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Second Event Team"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(otherEventId))
                .andExpect(jsonPath("$.joinCode").isNotEmpty());
    }

    @Test
    @DisplayName("选手账号导入导出与启停")
    void shouldManageContestants() throws Exception {
        String adminSession = login("admin", "admin12345");

        // 1. 创建账号并按状态筛选
        createContestant(adminSession, "field_200", "Import Team");
        mockMvc.perform(get("/api/v1/admin/contestants").param("q", "field_200")
                        .cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value("field_200"))
                .andExpect(jsonPath("$.items[0].team.name").value("Import Team"));

        // 2. 停用后账号无法登录
        String userId = read(mockMvc.perform(get("/api/v1/admin/contestants").param("q", "field_200")
                        .cookie(sessionCookie(adminSession))).andReturn(), "$.items[0].id");
        mockMvc.perform(post("/api/v1/admin/contestants/" + userId + "/disable")
                        .cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "field_200", "password", "secret123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));

        // 3. 导出 CSV 不包含密码字段
        MvcResult export = mockMvc.perform(get("/api/v1/admin/contestants/export")
                        .cookie(sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andReturn();
        String csv = export.getResponse().getContentAsString();
        assertThat(csv).contains("username,team,status,created,note");
        assertThat(csv).doesNotContain("password");
    }

    /** 登录并返回会话令牌，测试通过 Cookie 传递身份。 */
    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("tq_session");
        assertThat(cookie).as("登录应下发会话 Cookie").isNotNull();
        return cookie.getValue();
    }

    /** 创建选手账号。 */
    private void createContestant(String adminSession, String username, String teamName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", "secret123");
        payload.put("teamName", teamName);
        mockMvc.perform(post("/api/v1/admin/contestants")
                        .cookie(sessionCookie(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated());
    }

    /** 构造题目创建请求。 */
    private Map<String, Object> challengePayload(String code, String title, String category, int points) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("title", title);
        payload.put("category", category);
        payload.put("difficulty", "中等");
        payload.put("points", points);
        payload.put("bonuses", Map.of("first", 100, "second", 60, "third", 30, "other", 0));
        payload.put("description", "题目说明与线索");
        payload.put("answer", "flag{refraction}");
        payload.put("status", "draft");
        return payload;
    }

    /** 序列化为 JSON 字符串。 */
    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** 读取指定 JSON 路径的文本值。 */
    private String read(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    /** 由令牌构造 Cookie。 */
    private Cookie sessionCookie(String token) {
        return new Cookie("tq_session", token);
    }
}

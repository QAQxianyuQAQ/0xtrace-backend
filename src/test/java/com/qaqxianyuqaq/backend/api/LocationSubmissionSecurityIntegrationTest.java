package com.qaqxianyuqaq.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.enums.ContainerStatus;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeContainerInstanceMapper;
import com.qaqxianyuqaq.backend.security.CredentialCipher;
import com.qaqxianyuqaq.backend.util.TimeUtil;
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
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 判题安全回归：全部数据仅存在专用测试库，不调用正式赛事或 Docker。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:location_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class LocationSubmissionSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChallengeContainerInstanceMapper instances;
    @Autowired CredentialCipher cipher;

    @Test void allSixLocationChallengesRejectSharedLegacyAnswersWithoutAnyScore() throws Exception {
        Cookie admin = login("admin", "admin12345"), player = player(admin, "six_location");
        long before = count("submissions"), solves = count("solves");
        for (int i = 1; i <= 6; i++) {
            String code = "MISC26-0" + i;
            challenge(admin, code, "图寻", false);
            for (String answer : new String[]{"shared-legacy-answer", "arbitrary-answer"}) {
                answer(player, code, answer, "blocked-" + code + answer)
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOCATION_QR_REQUIRED"));
            }
        }
        assertThat(count("submissions")).isEqualTo(before);
        assertThat(count("solves")).isEqualTo(solves);
        mvc.perform(get("/api/v1/me/progress").cookie(player)).andExpect(jsonPath("$.score").value(0));
    }

    @Test void oldAnswerReplayCannotBypassChangeToLocationCategory() throws Exception {
        Cookie admin = login("admin", "admin12345"), player = player(admin, "category_guard");
        challenge(admin, "CATEGORY-GUARD", "misc", false);
        answer(player, "CATEGORY-GUARD", "shared-legacy-answer", "category-key").andExpect(status().isOk());
        // 模拟既有旧版本题目：保留原答案、提交和幂等记录，只切换为图寻规则。
        jdbc.update("UPDATE challenges SET category='图寻' WHERE code='CATEGORY-GUARD'");
        long before = count("submissions"), solves = count("solves");
        answer(player, "CATEGORY-GUARD", "shared-legacy-answer", "category-key")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOCATION_QR_REQUIRED"));
        assertThat(count("submissions")).isEqualTo(before);
        assertThat(count("solves")).isEqualTo(solves);
    }

    @Test void qrMustMatchChallengeAndRemainActiveEvenForCachedRetries() throws Exception {
        Cookie admin = login("admin", "admin12345"), player = player(admin, "qr_guard");
        challenge(admin, "QR-GUARD-A", "图寻", false);
        challenge(admin, "QR-GUARD-B", "图寻", false);
        String a = issue(admin, "QR-GUARD-A"), b = issue(admin, "QR-GUARD-B");
        assertThat(a.split("\\|")[3]).isNotEqualTo(b.split("\\|")[3]);
        location(player, a.replace("QR-GUARD-A", "QR-GUARD-B"), "qr-mismatch").andExpect(status().isBadRequest());
        location(player, "TRACEQUEST|LOCATION|QR-GUARD-A|" + "0".repeat(64), "qr-forged").andExpect(status().isBadRequest());
        location(player, a, "qr-first").andExpect(status().isOk()).andExpect(jsonPath("$.source").value("location"));
        long before = count("submissions"), solves = count("solves");
        location(player, a, "qr-first").andExpect(status().isOk());
        assertThat(count("submissions")).isEqualTo(before);
        assertThat(count("solves")).isEqualTo(solves);
        mvc.perform(delete("/api/v1/admin/challenges/QR-GUARD-A/location-qr").cookie(admin)).andExpect(status().isNoContent());
        location(player, a, "qr-first").andExpect(status().isBadRequest());
        String rotated = issue(admin, "QR-GUARD-A");
        assertThat(rotated).isNotEqualTo(a);
        location(player, a, "qr-new-key").andExpect(status().isBadRequest());
        jdbc.update("UPDATE location_tokens SET expires_at=? WHERE challenge_id=(SELECT id FROM challenges WHERE code=?) AND revoked_at IS NULL",
                TimeUtil.now().minusSeconds(1), "QR-GUARD-A");
        location(player, rotated, "qr-expired").andExpect(status().isBadRequest());
        assertThat(count("solves")).isEqualTo(solves);
    }

    @Test void qrChecksEventTimeWindowBeforeReplayingAnySuccessfulResponse() throws Exception {
        Cookie admin = login("admin", "admin12345"), player = player(admin, "window_guard");
        challenge(admin, "QR-WINDOW", "图寻", false);
        String payload = issue(admin, "QR-WINDOW");
        location(player, payload, "window-key").andExpect(status().isOk());
        try {
            jdbc.update("UPDATE events SET starts_at=?, ends_at=?", TimeUtil.now().plusHours(1), TimeUtil.now().plusHours(2));
            location(player, payload, "window-key").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SUBMISSION_CLOSED"));
            jdbc.update("UPDATE events SET starts_at=?, ends_at=?", TimeUtil.now().minusHours(2), TimeUtil.now().minusHours(1));
            location(player, payload, "window-key").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SUBMISSION_CLOSED"));
        } finally {
            jdbc.update("UPDATE events SET starts_at=?, ends_at=?", TimeUtil.now().minusHours(1), TimeUtil.now().plusDays(1));
        }
    }

    @Test void dockerFlagsAreTeamAndChallengeScopedAndOldCachedFlagsStopWorking() throws Exception {
        Cookie admin = login("admin", "admin12345"), alice = player(admin, "flag_alice"), bob = player(admin, "flag_bob");
        challenge(admin, "WEB-GUARD-A", "Web", true);
        challenge(admin, "WEB-GUARD-B", "Web", true);
        seedInstance("WEB-GUARD-A", "flag_alice", "sacc{alice-only}");
        seedInstance("WEB-GUARD-A", "flag_bob", "sacc{bob-only}");
        answer(alice, "WEB-GUARD-A", "sacc{bob-only}", "foreign-key").andExpect(status().isForbidden());
        answer(alice, "WEB-GUARD-A", "shared-legacy-answer", "shared-key").andExpect(status().isForbidden());
        answer(alice, "WEB-GUARD-B", "sacc{alice-only}", "wrong-challenge").andExpect(status().isConflict());
        answer(alice, "WEB-GUARD-A", "sacc{alice-only}", "valid-team-key").andExpect(status().isOk()).andExpect(jsonPath("$.result").value("correct"));
        answer(bob, "WEB-GUARD-A", "sacc{alice-only}", "stolen-key").andExpect(status().isForbidden());
        String team = jdbc.queryForObject("SELECT t.id FROM teams t JOIN team_members m ON t.id=m.team_id JOIN users u ON u.id=m.user_id WHERE u.username=?", String.class, "flag_alice");
        jdbc.update("UPDATE challenge_container_instances SET flag_secret=? WHERE team_id=?", cipher.encrypt("sacc{alice-rotated}"), team);
        long solves = count("solves");
        answer(alice, "WEB-GUARD-A", "sacc{alice-only}", "valid-team-key").andExpect(status().isForbidden());
        answer(alice, "WEB-GUARD-A", "sacc{alice-rotated}", "new-instance-key").andExpect(status().isOk());
        assertThat(count("solves")).isEqualTo(solves);
    }

    private void seedInstance(String code, String username, String flag) {
        var row = jdbc.queryForMap("SELECT c.id AS challenge_id,c.event_id,t.id AS team_id FROM challenges c JOIN teams t ON t.event_id=c.event_id JOIN team_members m ON m.team_id=t.id JOIN users u ON u.id=m.user_id WHERE c.code=? AND u.username=?", code, username);
        ChallengeContainerInstance instance = new ChallengeContainerInstance();
        instance.setId("inst_" + username); instance.setChallengeId((String) row.get("challenge_id"));
        instance.setEventId((String) row.get("event_id")); instance.setTeamId((String) row.get("team_id"));
        instance.setContainerName("local-fixture-" + username); instance.setDockerImage("fixture"); instance.setDockerTag("local");
        instance.setFlagEnvName("FLAG"); instance.setFlagSecret(cipher.encrypt(flag)); instance.setStatus(ContainerStatus.READY);
        instance.setContainerPort(80); instance.setExpiresAt(TimeUtil.now().plusHours(1)); instances.insert(instance);
    }
    private void challenge(Cookie admin, String code, String category, boolean docker) throws Exception {
        var body = new LinkedHashMap<String,Object>(Map.of("code",code,"title",code,"category",category,"difficulty","test","points",100,"status","draft"));
        if (docker) body.putAll(Map.of("dockerImage","fixture","dockerTag","local","flagEnvName","FLAG","flagPrefix","sacc"));
        else body.put("answer","shared-legacy-answer");
        mvc.perform(post("/api/v1/admin/challenges").cookie(admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/challenges/"+code+"/publish").cookie(admin)).andExpect(status().isOk());
    }
    private Cookie player(Cookie admin, String name) throws Exception {
        mvc.perform(post("/api/v1/admin/contestants").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username",name,"password","secret123","teamName",name+" Team")))).andExpect(status().isCreated());
        return login(name,"secret123");
    }
    private Cookie login(String name, String password) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("username",name,"password",password)))).andExpect(status().isOk()).andReturn().getResponse();
        String[] cookie=response.getHeader("Set-Cookie").split(";",2)[0].split("=",2); return new Cookie(cookie[0],cookie[1]);
    }
    private String issue(Cookie admin, String code) throws Exception {
        MvcResult result=mvc.perform(post("/api/v1/admin/challenges/"+code+"/location-qr").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn(); return json.readTree(result.getResponse().getContentAsByteArray()).path("payload").asText();
    }
    private org.springframework.test.web.servlet.ResultActions answer(Cookie player,String code,String answer,String key) throws Exception {
        return mvc.perform(post("/api/v1/challenges/"+code+"/submissions").cookie(player).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("answer",answer,"requestId",key))));
    }
    private org.springframework.test.web.servlet.ResultActions location(Cookie player,String payload,String key) throws Exception {
        return mvc.perform(post("/api/v1/location-verifications").cookie(player).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("payload",payload,"requestId",key))));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
}

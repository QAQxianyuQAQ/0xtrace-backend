package com.qaqxianyuqaq.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.BusinessException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.UnauthorizedException;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.NjuptAuthenticationClient;
import com.qaqxianyuqaq.backend.service.auth.impl.NjuptAccountService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:njuptauth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "tracequest.njupt.enabled=true", "tracequest.rate-limit.login-per-minute=1000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
class NjuptAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired NjuptAccountService accounts;
    @Autowired TraceQuestProperties properties;
    @MockitoBean NjuptAuthenticationClient school;

    private org.springframework.test.web.servlet.ResultActions schoolLogin(String subject, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/njupt/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", subject, "password", password))));
    }

    @Test
    void shouldCreateOnlyAContestantAndReuseTheSameIdentity() throws Exception {
        // 同名管理员不能被学号关联；学校密码不写入数据库或本站响应。
        User admin = new User();
        admin.setId("local-admin-same-school-name"); admin.setUsername("B25SAME");
        admin.setPasswordHash(encoder.encode("local-admin-password")); admin.setRole(UserRole.ADMIN);
        admin.setStatus(AccountStatus.ACTIVE); admin.setTokenVersion(0); users.insert(admin);
        var result = schoolLogin(" b25same ", "synthetic-school-password").andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("contestant")).andExpect(jsonPath("$.username").value("njupt:B25SAME"))
                .andReturn();
        var body = json.readTree(result.getResponse().getContentAsByteArray());
        String userId = body.path("userId").asText();
        assertThat(userId).isNotBlank().isNotEqualTo(admin.getId());
        var stored = users.selectById(userId);
        assertThat(encoder.matches("synthetic-school-password", stored.getPasswordHash())).isFalse();
        assertThat(jdbc.queryForObject("SELECT user_id FROM njupt_identities WHERE subject='B25SAME'", String.class)).isEqualTo(userId);
        verify(school).authenticate("B25SAME", "synthetic-school-password");
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("HttpOnly", "SameSite=").doesNotContain("synthetic-school-password");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic-school-password", "token", "passwordHash");
        String[] session = cookie.split(";", 2)[0].split("=", 2);
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(session[0], session[1])))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId));
        schoolLogin("B25SAME", "synthetic-school-password").andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='njupt:B25SAME'", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldRejectDisabledOrElevatedSchoolAccountsAndLocalPasswordBypass() throws Exception {
        User user = accounts.resolve("B25BLOCKED");
        user.setPasswordHash(encoder.encode("reset-local-password")); users.updateById(user);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", "njupt:B25BLOCKED", "password", "reset-local-password"))))
                .andExpect(status().isUnauthorized());
        user.setStatus(AccountStatus.DISABLED); users.updateById(user);
        schoolLogin("B25BLOCKED", "synthetic").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        user.setStatus(AccountStatus.ACTIVE); user.setRole(UserRole.ADMIN); users.updateById(user);
        schoolLogin("B25BLOCKED", "synthetic").andExpect(status().isForbidden());
    }

    @Test
    void shouldNotCreateAccountsOnInvalidOrIncompleteAuthentication() throws Exception {
        for (var error : new ErrorCode[]{ErrorCode.INVALID_CREDENTIALS, ErrorCode.NJUPT_CAPTCHA_REQUIRED, ErrorCode.NJUPT_AUTH_UNAVAILABLE}) {
            String subject = "B25FAIL" + error.ordinal();
            BusinessException failure = error == ErrorCode.INVALID_CREDENTIALS
                    ? new UnauthorizedException(error, "学号或学校密码错误") : new BusinessException(error);
            doThrow(failure).when(school).authenticate(subject, "synthetic");
            schoolLogin(subject, "synthetic").andExpect(status().is(error.status().value()))
                    .andExpect(jsonPath("$.error.code").value(error.name()));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM njupt_identities WHERE subject=?", Integer.class, subject)).isZero();
        }
        schoolLogin("../../invalid", "synthetic").andExpect(status().isBadRequest());
        schoolLogin("B25EMPTY", " ").andExpect(status().isBadRequest());
        schoolLogin("B25LONG", "x".repeat(257)).andExpect(status().isBadRequest());
    }

    @Test
    void shouldDeduplicateConcurrentFirstLoginsWithoutOrphanUsers() throws Exception {
        try (var executor = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<String>>();
            for (int i=0; i<6; i++) tasks.add(executor.submit(() -> { start.await(); return accounts.resolve("B25RACE").getId(); }));
            start.countDown();
            var ids = new java.util.HashSet<String>();
            for (var task : tasks) ids.add(task.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='njupt:B25RACE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM njupt_identities WHERE subject='B25RACE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldRollbackAReservedNameCollisionInsteadOfTakingOverAnAccount() {
        User user = new User(); user.setId("reserved-local-user"); user.setUsername("njupt:B25COLLISION");
        user.setPasswordHash(encoder.encode("local")); user.setRole(UserRole.CONTESTANT);
        user.setStatus(AccountStatus.ACTIVE); user.setTokenVersion(0); users.insert(user);
        assertThatThrownBy(() -> accounts.resolve("B25COLLISION")).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM njupt_identities WHERE subject='B25COLLISION'", Integer.class)).isZero();
    }

    @Test
    void shouldLockRepeatedBadPasswordsButNotCaptcha() throws Exception {
        int threshold = properties.getRateLimit().getLoginFailureThreshold();
        doThrow(new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, "学号或学校密码错误"))
                .when(school).authenticate("B25LOCK", "wrong");
        for (int i=0; i<threshold; i++) schoolLogin("B25LOCK", "wrong").andExpect(status().isUnauthorized());
        schoolLogin("B25LOCK", "wrong").andExpect(status().isTooManyRequests());
        verify(school, times(threshold)).authenticate("B25LOCK", "wrong");
        doThrow(new BusinessException(ErrorCode.NJUPT_CAPTCHA_REQUIRED)).when(school).authenticate("B25CAPTCHA", "synthetic");
        for (int i=0; i<threshold+1; i++) schoolLogin("B25CAPTCHA", "synthetic").andExpect(status().isConflict());
        doNothing().when(school).authenticate("B25CAPTCHA", "synthetic");
        schoolLogin("B25CAPTCHA", "synthetic").andExpect(status().isOk());
    }

    @Test
    void shouldKeepLocalAdminLoginAndCloseTheSchoolEndpointWhenDisabled() throws Exception {
        mvc.perform(get("/api/v1/auth/providers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.local").value(true)).andExpect(jsonPath("$.njupt").value(true));
        properties.getNjupt().setEnabled(false);
        try {
            mvc.perform(get("/api/v1/auth/providers")).andExpect(jsonPath("$.njupt").value(false));
            schoolLogin("B25OFF", "synthetic").andExpect(status().isForbidden());
            verifyNoInteractions(school);
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("username", "admin", "password", "admin12345"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("admin"));
        } finally { properties.getNjupt().setEnabled(true); }
    }
}

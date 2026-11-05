package com.qaqxianyuqaq.backend.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.profile.ProfileService;
import com.qaqxianyuqaq.backend.util.AvatarImageUtil;
import com.qaqxianyuqaq.backend.util.MinioUtil;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用独立 H2 随机账号与真实 MinIO 应用凭据验证文件链路，仅清理本次生成的对象。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:avatar_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "tracequest.rate-limit.avatar-upload-per-minute=100",
        "tracequest.security.enforce-same-origin=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TQ_AVATAR_IT_MINIO_URL", matches = ".+")
class AvatarStorageIntegrationTest {
    private static final String BUCKET = System.getenv("TQ_MINIO_BUCKET");
    private final Set<String> createdKeys = ConcurrentHashMap.newKeySet();
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired ProfileService profiles;
    @Autowired MinioClient minio;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracequest.minio.endpoint", () -> System.getenv("TQ_AVATAR_IT_MINIO_URL"));
        registry.add("tracequest.minio.access-key", () -> System.getenv("TQ_MINIO_ACCESS_KEY"));
        registry.add("tracequest.minio.secret-key", () -> System.getenv("TQ_MINIO_SECRET_KEY"));
        registry.add("tracequest.minio.bucket", () -> BUCKET);
    }

    @Test
    void uploadsReplacesAndRollsBackWithoutLosingThePreviousAvatar() throws Exception {
        Cookie session = login();
        User user = users.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, "admin"));
        String first = upload(session);
        byte[] original = mvc.perform(get(first).cookie(session)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(MinioUtil.readObject(minio, BUCKET,
                AvatarImageUtil.previousStorageKey(user.getId(), first), AvatarImageUtil.MAX_BYTES)).isEqualTo(original);
        String second = upload(session);
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get(first).cookie(session)).andExpect(status().isNotFound());
        assertThatThrownBy(() -> MinioUtil.readObject(minio, BUCKET,
                AvatarImageUtil.previousStorageKey(user.getId(), first), AvatarImageUtil.MAX_BYTES))
                .isInstanceOf(ErrorResponseException.class);
        AtomicReference<String> rolledBack = new AtomicReference<>();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            rolledBack.set(profiles.uploadAvatar(new UserPrincipal(user), image()).avatarUrl());
            transaction.setRollbackOnly();
        });
        assertThat(users.selectById(user.getId()).getAvatarUrl()).isEqualTo(second);
        mvc.perform(get(second).cookie(session)).andExpect(status().isOk());
        assertThatThrownBy(() -> MinioUtil.readObject(minio, BUCKET,
                AvatarImageUtil.previousStorageKey(user.getId(), rolledBack.get()), AvatarImageUtil.MAX_BYTES))
                .isInstanceOf(ErrorResponseException.class);
        assertThat(users.selectById(user.getId()).getRole()).isEqualTo(user.getRole());
    }

    @Test
    void rejectsAnonymousCrossOriginMissingAndForgedFiles() throws Exception {
        mvc.perform(multipart("/api/v1/me/avatar").file(image()).header("Host", "localhost").header("Origin", "http://localhost"))
                .andExpect(status().isUnauthorized());
        Cookie session = login();
        mvc.perform(multipart("/api/v1/me/avatar").file(image()).cookie(session)
                        .header("Host", "localhost").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/me/avatar").cookie(session).header("Host", "localhost").header("Origin", "http://localhost"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/me/avatar").file(new MockMultipartFile("file", "fake.png", "image/png",
                        "not an image".getBytes())).cookie(session).header("Host", "localhost").header("Origin", "http://localhost"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/me/avatar").contentType("application/json").content("{}")
                        .cookie(session).header("Host", "localhost").header("Origin", "http://localhost"))
                .andExpect(status().isUnsupportedMediaType());
    }

    private Cookie login() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").header("Host", "localhost").header("Origin", "http://localhost")
                        .contentType("application/json").content("{\"username\":\"admin\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie("tq_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String upload(Cookie session) throws Exception {
        var response = mvc.perform(multipart("/api/v1/me/avatar").file(image()).cookie(session)
                        .header("Host", "localhost").header("Origin", "http://localhost"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String url = json.readTree(response.getContentAsByteArray()).path("avatarUrl").asText();
        assertThat(url).startsWith("/api/v1/avatars/usr_");
        createdKeys.add("challenges/profile-avatars/" + url.substring("/api/v1/avatars/".length()) + ".png");
        return url;
    }

    private MockMultipartFile image() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(24, 48, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return new MockMultipartFile("file", "avatar.png", "image/png", bytes.toByteArray());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @AfterAll
    void removeOnlyTheObjectsCreatedByThisTest() throws Exception {
        for (String key : createdKeys) {
            MinioUtil.removeObject(minio, BUCKET, key);
        }
    }
}

package com.qaqxianyuqaq.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.exception.BusinessException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.RateLimitedException;
import com.qaqxianyuqaq.backend.exception.UnauthorizedException;
import com.qaqxianyuqaq.backend.util.NjuptCredentialUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** 只向学校固定 HTTPS 地址发送认证请求；不保存密码、密文、学校令牌或响应正文。 */
@Component
public class NjuptAuthenticationClient {
    private static final String ENDPOINT = "https://i.njupt.edu.cn/ssoLogin/login";
    private final ObjectMapper mapper;
    private final TraceQuestProperties properties;
    private final OkHttpClient client;
    private final String endpoint;

    @Autowired
    public NjuptAuthenticationClient(ObjectMapper mapper, TraceQuestProperties properties) {
        this(mapper, properties, new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(), ENDPOINT);
    }

    /** 仅包内测试可替换传输端点，部署配置不开放目标地址选项。 */
    NjuptAuthenticationClient(ObjectMapper mapper, TraceQuestProperties properties,
                              OkHttpClient client, String endpoint) {
        this.mapper = mapper;
        this.properties = properties;
        this.client = client;
        this.endpoint = endpoint;
    }

    public void authenticate(String username, String password) {
        try {
            var config = properties.getNjupt();
            var body = mapper.createObjectNode().put("checkKey", config.getCheckKey())
                    .put("username", NjuptCredentialUtil.encrypt(username, config.getCheckKey()))
                    .put("password", NjuptCredentialUtil.encrypt(password, config.getCheckKey()))
                    .put("appId", config.getAppId()).put("mode", "none");
            body.putNull("captchaVerification");
            Request request = new Request.Builder().url(endpoint)
                    .header("Tenant-Id", "0").header("Accept", "application/json")
                    .header("Origin", "https://i.njupt.edu.cn")
                    .header("Referer", "https://i.njupt.edu.cn/user-login/user/login")
                    .post(RequestBody.create(mapper.writeValueAsBytes(body), MediaType.get("application/json;charset=UTF-8")))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 429) throw new RateLimitedException(60);
                if (response.code() == 401) throw invalidCredentials();
                if (!response.isSuccessful() || response.body() == null) throw unavailable();
                byte[] bytes = response.body().byteStream().readNBytes(65537);
                if (bytes.length > 65536) throw unavailable();
                JsonNode result = mapper.readTree(bytes);
                if (result == null || !result.isObject()) throw unavailable();
                if ("sz".equals(result.path("codeType").asText())) {
                    throw new BusinessException(ErrorCode.NJUPT_CAPTCHA_REQUIRED);
                }
                if (!result.path("code").isIntegralNumber() || !result.path("code").canConvertToInt() || !result.path("success").isBoolean()) throw unavailable();
                int code = result.path("code").intValue();
                if (code == 511 || code == 512) throw new BusinessException(ErrorCode.NJUPT_ACCOUNT_ACTION_REQUIRED);
                if (!result.path("success").asBoolean(false) || code != 200) throw invalidCredentials();
                JsonNode identity = result.path("result");
                if (!identity.path("token").isTextual() || identity.path("token").asText().isBlank()) throw unavailable();
                JsonNode subject = identity.path("userInfo").path("username");
                if (!subject.isMissingNode() && (!subject.isTextual() || !username.equalsIgnoreCase(subject.asText()))) {
                    throw unavailable();
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, "学号或学校密码错误");
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.NJUPT_AUTH_UNAVAILABLE);
    }
}

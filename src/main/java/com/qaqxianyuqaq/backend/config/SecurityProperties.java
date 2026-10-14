package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 安全相关参数（对应 tracequest.security）。 */
@Getter
@Setter
public class SecurityProperties {

    /** 题目答案加密密钥，必须由环境变量 TQ_ANSWER_SECRET 注入；此处不提供任何内置默认值。 */
    private String answerSecret;

    /** 会话 Cookie 名称。 */
    private String cookieName = "tq_session";

    /** 是否在 Cookie 上启用 Secure（生产必须开启）。 */
    private boolean cookieSecure = false;

    private String cookieSameSite = "Strict";

    /** 写请求来源白名单，为空时退化为同主机校验。 */
    private List<String> allowedOrigins = List.of();

    /** 是否强制同源校验；本地自动化测试可关闭。 */
    private boolean enforceSameOrigin = true;

    /** 是否强制密钥强度校验：开启后弱密钥将直接导致启动失败（生产 profile 自动开启）。 */
    private boolean strictSecretCheck = false;

    /** 已知的弱密钥/示例密钥清单，命中即视为不安全。 */
    private List<String> weakSecrets = List.of(
            "tracequest-local-development-secret-only",
            "tracequest-local-development-answer-secret",
            "tracequest-development-secret-change-me",
            "tracequest-answer-secret-change-me",
            "tracequest-test-jwt-secret",
            "tracequest-test-answer-secret",
            "admin12345",
            "change-me",
            "changeme",
            "secret",
            "password");
}

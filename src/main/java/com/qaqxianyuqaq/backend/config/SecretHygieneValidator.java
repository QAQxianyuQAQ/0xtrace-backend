package com.qaqxianyuqaq.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动期密钥体检。
 *
 * <p>仓库中不保存任何真实凭据：本项目通过环境变量注入 JWT 签名密钥、答案加密密钥、数据库口令与对象存储口令。
 * 本组件在容器启动时统一校验这些密钥：生产 profile 下命中弱密钥直接失败，非生产环境仅输出告警，
 * 避免把开发默认值带上线。</p>
 */
@Component
public class SecretHygieneValidator {

    private static final Logger log = LoggerFactory.getLogger(SecretHygieneValidator.class);

    /** 触发严格校验的 profile 名称关键字。 */
    private static final String PRODUCTION_PROFILE = "prod";

    private final TraceQuestProperties properties;

    private final Environment environment;

    public SecretHygieneValidator(TraceQuestProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /** 启动前执行校验，先于任何数据初始化动作。 */
    @PostConstruct
    public void validate() {
        // 1. 判定是否处于生产环境：profile 命中 prod 或显式开启严格校验
        boolean production = isProduction();

        // 2. 收集所有不安全的密钥问题
        List<String> issues = new ArrayList<>();
        checkSecret("tracequest.jwt.secret", properties.getJwt().getSecret(), issues);
        checkSecret("tracequest.security.answer-secret", properties.getSecurity().getAnswerSecret(), issues);
        checkSecret("tracequest.bootstrap.admin-password", properties.getBootstrap().getAdminPassword(), issues);
        checkPresent("spring.datasource.password", environment.getProperty("spring.datasource.password"), issues);
        checkPresent("tracequest.minio.access-key", properties.getMinio().getAccessKey(), issues);
        checkPresent("tracequest.minio.secret-key", properties.getMinio().getSecretKey(), issues);
        if (properties.getBootstrap().isEnabled()) {
            issues.add("tracequest.bootstrap.enabled=true：生产环境必须关闭初始化数据装配，账号应由运维流程创建");
        }

        // 3. 生产环境发现问题直接终止启动，避免弱密钥上线
        if (production && !issues.isEmpty()) {
            throw new IllegalStateException("检测到不安全的密钥配置，已拒绝启动：" + String.join("；", issues));
        }

        // 4. 非生产环境仅告警，方便本地开发
        issues.forEach(issue -> log.warn("密钥体检告警（非生产环境不阻断启动）：{}", issue));
    }

    /** 判断当前是否按生产环境标准校验。 */
    private boolean isProduction() {
        boolean profileProduction = false;
        for (String profile : environment.getActiveProfiles()) {
            if (profile.toLowerCase(Locale.ROOT).contains(PRODUCTION_PROFILE)) {
                profileProduction = true;
                break;
            }
        }
        return profileProduction || properties.getSecurity().isStrictSecretCheck();
    }

    /** 校验单个密钥：缺失、过短或命中弱密钥清单都记为问题。 */
    private void checkSecret(String name, String value, List<String> issues) {
        if (value == null || value.isBlank()) {
            issues.add(name + " 未配置，必须通过环境变量注入");
            return;
        }
        // 占位符未被解析（例如 ${TQ_JWT_SECRET} 原样保留）同样视为未注入
        if (value.contains("${")) {
            issues.add(name + " 仍是未解析的占位符，请确认对应环境变量已注入");
            return;
        }
        if (value.length() < 16) {
            issues.add(name + " 长度不足 16 位，建议使用 32 字节以上随机值");
        }
        if (properties.getSecurity().getWeakSecrets().stream().anyMatch(weak -> weak.equalsIgnoreCase(value))) {
            issues.add(name + " 使用了仓库中登记的弱密钥/示例值");
        }
    }

    /** 校验必需的凭据是否已注入（不要求长度）。 */
    private void checkPresent(String name, String value, List<String> issues) {
        if (value == null || value.isBlank()) {
            issues.add(name + " 未配置，必须通过环境变量注入");
        }
    }
}

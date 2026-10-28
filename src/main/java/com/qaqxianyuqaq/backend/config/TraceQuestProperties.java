package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/** 应用配置项集中定义，前缀统一为 tracequest。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tracequest")
public class TraceQuestProperties {

    @NestedConfigurationProperty
    private final JwtProperties jwt = new JwtProperties();

    @NestedConfigurationProperty
    private final SecurityProperties security = new SecurityProperties();

    @NestedConfigurationProperty
    private final MinioProperties minio = new MinioProperties();

    @NestedConfigurationProperty
    private final RateLimitProperties rateLimit = new RateLimitProperties();

    @NestedConfigurationProperty
    private final StorageProperties storage = new StorageProperties();

    @NestedConfigurationProperty
    private final BootstrapProperties bootstrap = new BootstrapProperties();

    @NestedConfigurationProperty
    private final RuntimeProperties runtime = new RuntimeProperties();
}

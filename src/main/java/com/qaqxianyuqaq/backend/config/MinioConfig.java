package com.qaqxianyuqaq.backend.config;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO 配置：客户端与桶初始化。 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    /** 构建 MinIO 客户端，仅保存连接参数，不在启动阶段发起连接。 */
    @Bean
    public MinioClient minioClient(TraceQuestProperties properties) {
        // 凭据未配置时使用占位值，保证容器仍可启动；真正调用对象存储时会抛出明确的存储异常
        String accessKey = blankToPlaceholder(properties.getMinio().getAccessKey());
        String secretKey = blankToPlaceholder(properties.getMinio().getSecretKey());
        return MinioClient.builder()
                .endpoint(properties.getMinio().getEndpoint())
                .credentials(accessKey, secretKey)
                .build();
    }

    /** 空凭据回落为占位值，避免因缺少环境变量导致上下文启动失败。 */
    private String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "unconfigured" : value;
    }

    /** 启动时确保桶存在；对象存储不可用时只告警，不阻断应用启动。 */
    @Bean
    public ApplicationRunner storageBucketInitializer(MinioClient minioClient, TraceQuestProperties properties) {
        return args -> {
            if (!properties.getMinio().isInitOnStartup()) {
                return;
            }
            try {
                com.qaqxianyuqaq.backend.util.MinioUtil.ensureBucket(minioClient,
                        properties.getMinio().getBucket());
                log.info("MinIO 存储桶已就绪: {}", properties.getMinio().getBucket());
            } catch (Exception exception) {
                log.warn("MinIO 初始化失败，文件上传能力暂不可用: {}", exception.getMessage());
            }
        };
    }
}

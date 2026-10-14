package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/** MinIO 对象存储参数（对应 tracequest.minio）。 */
@Getter
@Setter
public class MinioProperties {

    private String endpoint = "http://localhost:9000";

    private String accessKey = "minioadmin";

    private String secretKey = "minioadmin";

    /** 题目附件与封面统一存放的桶名。 */
    private String bucket = "tracequest";

    /** 预签名下载地址有效期。 */
    private Duration presignTtl = Duration.ofMinutes(5);

    /** 启动时是否自动创建桶；MinIO 不可用时只告警不阻断启动。 */
    private boolean initOnStartup = true;
}

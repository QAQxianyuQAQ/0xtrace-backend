package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/** JWT 签发参数（对应 tracequest.jwt）。 */
@Getter
@Setter
public class JwtProperties {

    /** 签名密钥，必须由环境变量 TQ_JWT_SECRET 注入；此处不提供任何内置默认值。 */
    private String secret;

    private String issuer = "tracequest";

    /** 令牌有效期，默认 8 小时。 */
    private Duration ttl = Duration.ofHours(8);
}

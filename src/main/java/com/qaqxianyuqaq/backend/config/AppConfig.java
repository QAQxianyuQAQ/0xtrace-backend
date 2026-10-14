package com.qaqxianyuqaq.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 应用基础配置：注册配置属性绑定。 */
@Configuration
@EnableConfigurationProperties(TraceQuestProperties.class)
public class AppConfig {
}

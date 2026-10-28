package com.qaqxianyuqaq.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用基础配置：注册配置属性绑定。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(TraceQuestProperties.class)
public class AppConfig {
}

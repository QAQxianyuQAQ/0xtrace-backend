package com.qaqxianyuqaq.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * TraceQuest 竞赛平台后端启动类。
 *
 * <p>工程遵循严格 MVC 分层：controller → service → repository，跨切面逻辑统一放在 aspect/util。</p>
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.qaqxianyuqaq.backend.repository")
public class BackendApplication {

    public static void main(String[] args) {
        // 启动 Spring Boot 应用容器
        SpringApplication.run(BackendApplication.class, args);
    }
}

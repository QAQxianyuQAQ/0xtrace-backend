package com.qaqxianyuqaq.backend.controller.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 健康检查接口，供单机 Docker 与反向代理探活使用。 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /** 存活探针：返回固定结构，不触发任何业务查询。 */
    @GetMapping
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "tracequest-backend");
    }
}

package com.qaqxianyuqaq.backend.controller.event;

import com.qaqxianyuqaq.backend.domain.dto.event.UpdateEventDTO;
import com.qaqxianyuqaq.backend.domain.vo.event.EventVO;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.service.event.EventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 后台赛事配置接口。 */
@RestController
@RequestMapping("/api/v1/admin/event")
public class AdminEventController {

    private final EventService eventService;

    public AdminEventController(EventService eventService) {
        this.eventService = eventService;
    }

    /** 读取完整赛事配置。 */
    @GetMapping
    public EventVO current() {
        return eventService.adminEvent();
    }

    /** 更新赛事名称、状态、时区与起止时间。 */
    @PatchMapping
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "event.updated", entityType = "event")
    public EventVO update(@Valid @RequestBody UpdateEventDTO request) {
        return eventService.update(request);
    }
}

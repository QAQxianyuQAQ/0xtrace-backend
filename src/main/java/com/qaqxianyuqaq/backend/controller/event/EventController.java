package com.qaqxianyuqaq.backend.controller.event;

import com.qaqxianyuqaq.backend.domain.vo.event.EventVO;

import com.qaqxianyuqaq.backend.service.event.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开赛事信息接口。 */
@RestController
@RequestMapping("/api/v1/event")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /** 赛事名称、公开状态、起止时间与时区。 */
    @GetMapping
    public EventVO current() {
        return eventService.publicEvent();
    }
}

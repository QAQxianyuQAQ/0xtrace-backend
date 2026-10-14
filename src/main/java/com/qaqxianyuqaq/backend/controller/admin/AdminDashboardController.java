package com.qaqxianyuqaq.backend.controller.admin;

import com.qaqxianyuqaq.backend.domain.vo.admin.DashboardVO;

import com.qaqxianyuqaq.backend.service.admin.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 后台总览接口。 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final DashboardService dashboardService;

    public AdminDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 总览卡片：赛事、题目/账号/解题指标与近期活动摘要。 */
    @GetMapping
    public DashboardVO dashboard() {
        return dashboardService.dashboard();
    }
}

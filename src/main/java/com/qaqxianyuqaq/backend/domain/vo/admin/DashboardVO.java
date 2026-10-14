package com.qaqxianyuqaq.backend.domain.vo.admin;

import com.qaqxianyuqaq.backend.domain.vo.event.EventVO;

import java.util.List;

/** 后台总览响应。 */
public record DashboardVO(EventVO event, MetricsVO metrics, List<ActivityVO> recentActivity) {
}

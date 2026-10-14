package com.qaqxianyuqaq.backend.service.admin;

import com.qaqxianyuqaq.backend.domain.vo.admin.DashboardVO;

/** 后台总览服务。 */
public interface DashboardService {

    /** 汇总题目、账号、解题与近期活动指标。 */
    DashboardVO dashboard();
}

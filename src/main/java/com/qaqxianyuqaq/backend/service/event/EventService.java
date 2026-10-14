package com.qaqxianyuqaq.backend.service.event;

import com.qaqxianyuqaq.backend.domain.dto.event.UpdateEventDTO;
import com.qaqxianyuqaq.backend.domain.vo.event.EventVO;

import com.qaqxianyuqaq.backend.domain.entity.EventConfig;

/** 赛事配置服务。 */
public interface EventService {

    /** 读取当前赛事实体，缺失时抛出 404。 */
    EventConfig requireCurrent();

    /** 公开赛事信息。 */
    EventVO publicEvent();

    /** 后台赛事配置。 */
    EventVO adminEvent();

    /** 更新赛事配置并校验状态流转与时间区间。 */
    EventVO update(UpdateEventDTO request);
}

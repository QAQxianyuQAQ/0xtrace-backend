package com.qaqxianyuqaq.backend.service.event;

import com.qaqxianyuqaq.backend.domain.dto.event.CreateEventDTO;
import com.qaqxianyuqaq.backend.domain.dto.event.UpdateEventDTO;
import com.qaqxianyuqaq.backend.domain.vo.event.EventVO;

import com.qaqxianyuqaq.backend.domain.entity.EventConfig;

/** 赛事配置服务。 */
public interface EventService {

    /** 读取当前赛事实体，缺失时抛出 404。 */
    EventConfig requireCurrent();

    /** 按赛事标识读取赛事实体，用于赛事内队伍与榜单操作。 */
    EventConfig requireById(String eventId);

    /** 公开赛事信息。 */
    EventVO publicEvent();

    /** 后台赛事配置。 */
    EventVO adminEvent();

    /** 创建赛事并设置队伍人数上限。 */
    EventVO create(CreateEventDTO request);

    /** 更新赛事配置并校验状态流转与时间区间。 */
    EventVO update(UpdateEventDTO request);
}

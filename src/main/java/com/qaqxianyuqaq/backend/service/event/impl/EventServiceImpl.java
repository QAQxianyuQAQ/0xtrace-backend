package com.qaqxianyuqaq.backend.service.event.impl;

import com.qaqxianyuqaq.backend.domain.dto.event.UpdateEventDTO;
import com.qaqxianyuqaq.backend.domain.vo.event.EventVO;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.event.EventMapper;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.EnumUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** 赛事配置服务实现。 */
@Service
public class EventServiceImpl implements EventService {

    /** 允许的赛事状态流转：setup → open → paused → open → closed。 */
    private static final Map<EventStatus, Set<EventStatus>> ALLOWED_TRANSITIONS = buildTransitions();

    private final EventMapper eventMapper;

    private final CacheService cacheService;

    public EventServiceImpl(EventMapper eventMapper, CacheService cacheService) {
        this.eventMapper = eventMapper;
        this.cacheService = cacheService;
    }

    @Override
    public EventConfig requireCurrent() {
        // 演示与赛事部署均只维护一条当前赛事记录
        EventConfig event = eventMapper.selectOne(Wrappers.<EventConfig>lambdaQuery()
                .orderByAsc(EventConfig::getCreatedAt)
                .last("LIMIT 1"));
        if (event == null) {
            throw new NotFoundException("赛事尚未配置");
        }
        return event;
    }

    @Override
    public EventVO publicEvent() {
        // 1. 读取当前赛事
        EventConfig event = requireCurrent();

        // 2. 公开信息与后台信息字段一致，不额外暴露敏感内容
        return toVO(event);
    }

    @Override
    public EventVO adminEvent() {
        // 1. 后台读取当前赛事配置
        return toVO(requireCurrent());
    }

    @Override
    @Transactional
    public EventVO update(UpdateEventDTO request) {
        // 1. 读取当前赛事配置
        EventConfig event = requireCurrent();

        // 2. 校验并应用名称、时区
        if (request.name() != null && !request.name().isBlank()) {
            event.setName(request.name().trim());
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            String timezone = request.timezone().trim();
            if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
                throw new ValidationFailedException("timezone", "未知的 IANA 时区标识");
            }
            event.setTimezone(timezone);
        }

        // 3. 校验并应用起止时间，开始时间必须早于结束时间
        LocalDateTime startsAt = request.startsAt() == null ? event.getStartsAt() : request.startsAt();
        LocalDateTime endsAt = request.endsAt() == null ? event.getEndsAt() : request.endsAt();
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new ValidationFailedException("endsAt", "开始时间必须早于结束时间");
        }
        event.setStartsAt(startsAt);
        event.setEndsAt(endsAt);

        // 4. 校验状态流转合法性，禁止从 closed 直接回到 open
        if (request.status() != null && !request.status().isBlank()) {
            EventStatus target = EnumUtil.parse(EventStatus.class, request.status(), "status");
            EventStatus current = event.getStatus();
            if (target != current && !ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
                throw new ConflictException(ErrorCode.EVENT_STATE_CONFLICT,
                        "赛事状态不允许从 " + current.apiValue() + " 变更为 " + target.apiValue());
            }
            event.setStatus(target);
        }

        // 5. 落库并刷新更新时间
        event.setUpdatedAt(TimeUtil.now());
        eventMapper.updateById(event);

        // 6. 删除赛事缓存，使选手端尽快看到新配置
        cacheService.delete(CacheKeys.CURRENT_EVENT);
        return toVO(event);
    }

    /** 构建允许的状态流转表。 */
    private static Map<EventStatus, Set<EventStatus>> buildTransitions() {
        Map<EventStatus, Set<EventStatus>> transitions = new EnumMap<>(EventStatus.class);
        transitions.put(EventStatus.SETUP, Set.of(EventStatus.OPEN, EventStatus.CLOSED));
        transitions.put(EventStatus.OPEN, Set.of(EventStatus.PAUSED, EventStatus.CLOSED));
        transitions.put(EventStatus.PAUSED, Set.of(EventStatus.OPEN, EventStatus.CLOSED));
        transitions.put(EventStatus.CLOSED, Set.of());
        return transitions;
    }

    /** 实体转响应模型。 */
    private EventVO toVO(EventConfig event) {
        return new EventVO(event.getId(), event.getName(), event.getStatus().apiValue(),
                event.getTimezone(), event.getStartsAt(), event.getEndsAt(), event.getUpdatedAt());
    }
}

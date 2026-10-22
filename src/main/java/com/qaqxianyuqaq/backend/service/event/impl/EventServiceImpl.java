package com.qaqxianyuqaq.backend.service.event.impl;

import com.qaqxianyuqaq.backend.domain.dto.event.CreateEventDTO;
import com.qaqxianyuqaq.backend.domain.dto.event.UpdateEventDTO;
import com.qaqxianyuqaq.backend.domain.vo.event.EventVO;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.TeamMember;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.event.EventMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMemberMapper;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.EnumUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 赛事配置服务实现。 */
@Service
public class EventServiceImpl implements EventService {

    /** 允许的赛事状态流转：setup → open → paused → open → closed。 */
    private static final Map<EventStatus, Set<EventStatus>> ALLOWED_TRANSITIONS = buildTransitions();

    private final EventMapper eventMapper;

    private final CacheService cacheService;

    private final TeamMapper teamMapper;

    private final TeamMemberMapper teamMemberMapper;

    public EventServiceImpl(EventMapper eventMapper, CacheService cacheService,
                            TeamMapper teamMapper, TeamMemberMapper teamMemberMapper) {
        this.eventMapper = eventMapper;
        this.cacheService = cacheService;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    @Override
    public EventConfig requireCurrent() {
        // 1. 以最近创建且已维护的赛事作为当前赛事，便于管理员新建赛事后立即切换管理上下文
        EventConfig event = eventMapper.selectOne(Wrappers.<EventConfig>lambdaQuery()
                .orderByDesc(EventConfig::getCreatedAt)
                .orderByDesc(EventConfig::getId)
                .last("LIMIT 1"));
        if (event == null) {
            throw new NotFoundException("赛事尚未配置");
        }
        return event;
    }

    @Override
    public EventConfig requireById(String eventId) {
        // 1. 按赛事标识读取配置，所有赛事内队伍操作都必须限定到明确赛事
        EventConfig event = eventId == null ? null : eventMapper.selectById(eventId);
        if (event == null) {
            throw new NotFoundException("赛事不存在");
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
    public EventVO create(CreateEventDTO request) {
        // 1. 校验赛事名称与队伍人数上限，保证后续队伍容量规则可用
        if (request.name() == null || request.name().isBlank()) {
            throw new ValidationFailedException("name", "赛事名称不能为空");
        }
        if (request.maxTeamSize() == null || request.maxTeamSize() < 1 || request.maxTeamSize() > 100) {
            throw new ValidationFailedException("maxTeamSize", "队伍人数上限需为 1-100");
        }

        // 2. 校验时区与起止时间，避免创建不可用赛事
        String timezone = request.timezone() == null || request.timezone().isBlank()
                ? "Asia/Shanghai" : request.timezone().trim();
        validateTimezone(timezone);
        validateTimeRange(request.startsAt(), request.endsAt());

        // 3. 组装赛事实体，新赛事默认处于配置阶段
        EventConfig event = new EventConfig();
        event.setId(IdGenerator.next("evt"));
        event.setName(request.name().trim());
        event.setStatus(EventStatus.SETUP);
        event.setTimezone(timezone);
        event.setStartsAt(request.startsAt());
        event.setEndsAt(request.endsAt());
        event.setMaxTeamSize(request.maxTeamSize());
        event.setCreatedAt(TimeUtil.now());
        event.setUpdatedAt(TimeUtil.now());

        // 4. 保存赛事并清理当前赛事缓存
        eventMapper.insert(event);
        cacheService.delete(CacheKeys.CURRENT_EVENT);
        return toVO(event);
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
            validateTimezone(timezone);
            event.setTimezone(timezone);
        }

        // 3. 校验并应用起止时间，开始时间必须早于结束时间
        LocalDateTime startsAt = request.startsAt() == null ? event.getStartsAt() : request.startsAt();
        LocalDateTime endsAt = request.endsAt() == null ? event.getEndsAt() : request.endsAt();
        validateTimeRange(startsAt, endsAt);
        event.setStartsAt(startsAt);
        event.setEndsAt(endsAt);

        // 4. 校验新的人数上限不会低于赛事内任何现有队伍人数
        if (request.maxTeamSize() != null) {
            validateReducedTeamCapacity(event.getId(), request.maxTeamSize());
            event.setMaxTeamSize(request.maxTeamSize());
        }

        // 5. 校验状态流转合法性，禁止从 closed 直接回到 open
        if (request.status() != null && !request.status().isBlank()) {
            EventStatus target = EnumUtil.parse(EventStatus.class, request.status(), "status");
            EventStatus current = event.getStatus();
            if (target != current && !ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
                throw new ConflictException(ErrorCode.EVENT_STATE_CONFLICT,
                        "赛事状态不允许从 " + current.apiValue() + " 变更为 " + target.apiValue());
            }
            event.setStatus(target);
        }

        // 6. 落库并刷新更新时间
        event.setUpdatedAt(TimeUtil.now());
        eventMapper.updateById(event);

        // 7. 删除赛事缓存，使选手端尽快看到新配置
        cacheService.delete(CacheKeys.CURRENT_EVENT);
        return toVO(event);
    }

    /** 校验 IANA 时区标识。 */
    private void validateTimezone(String timezone) {
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw new ValidationFailedException("timezone", "未知的 IANA 时区标识");
        }
    }

    /** 校验赛事起止时间。 */
    private void validateTimeRange(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new ValidationFailedException("endsAt", "开始时间必须早于结束时间");
        }
    }

    /** 校验缩小队伍上限时不会使已有成员超出容量。 */
    private void validateReducedTeamCapacity(String eventId, int maxTeamSize) {
        List<String> teamIds = teamMapper.selectList(Wrappers.<Team>lambdaQuery()
                        .eq(Team::getEventId, eventId))
                .stream().map(Team::getId).toList();
        if (teamIds.isEmpty()) {
            return;
        }
        long oversizedTeams = teamMemberMapper.selectList(Wrappers.<TeamMember>lambdaQuery()
                        .in(TeamMember::getTeamId, teamIds))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(TeamMember::getTeamId,
                        java.util.stream.Collectors.counting()))
                .values().stream()
                .filter(count -> count > maxTeamSize)
                .count();
        if (oversizedTeams > 0) {
            throw new ConflictException("队伍人数上限不能低于现有队伍成员数");
        }
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
                event.getTimezone(), event.getStartsAt(), event.getEndsAt(), event.getMaxTeamSize(),
                event.getUpdatedAt());
    }
}

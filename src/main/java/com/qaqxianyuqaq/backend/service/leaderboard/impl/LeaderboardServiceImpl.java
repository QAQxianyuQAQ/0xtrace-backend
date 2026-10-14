package com.qaqxianyuqaq.backend.service.leaderboard.impl;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.leaderboard.ScoreAdjustmentDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardItemVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardRowVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardStatsVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.ScoreEventVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.TeamDetailVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.TeamScoreVO;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.ScoreAdjustment;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.ScoreEventType;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.leaderboard.ScoreAdjustmentMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.service.leaderboard.LeaderboardService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.JsonUtil;
import com.qaqxianyuqaq.backend.util.PageUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 排行榜与计分管理服务实现。
 *
 * <p>排名规则固定为：总分降序 → 解题数降序 → 达到该分数的时间升序，与契约 8.2 一致。</p>
 */
@Service
public class LeaderboardServiceImpl implements LeaderboardService {

    /** 榜单缓存有效期，短暂缓存以削峰。 */
    private static final Duration LEADERBOARD_TTL = Duration.ofSeconds(30);

    /** 队伍模式标识。 */
    private static final String SCOPE_TEAM = "team";

    /** 个人模式标识。 */
    private static final String SCOPE_INDIVIDUAL = "individual";

    private final SolveMapper solveMapper;

    private final ScoreAdjustmentMapper scoreAdjustmentMapper;

    private final TeamMapper teamMapper;

    private final UserMapper userMapper;

    private final ChallengeMapper challengeMapper;

    private final EventService eventService;

    private final CacheService cacheService;

    public LeaderboardServiceImpl(SolveMapper solveMapper, ScoreAdjustmentMapper scoreAdjustmentMapper,
                                  TeamMapper teamMapper, UserMapper userMapper, ChallengeMapper challengeMapper,
                                  EventService eventService, CacheService cacheService) {
        this.solveMapper = solveMapper;
        this.scoreAdjustmentMapper = scoreAdjustmentMapper;
        this.teamMapper = teamMapper;
        this.userMapper = userMapper;
        this.challengeMapper = challengeMapper;
        this.eventService = eventService;
        this.cacheService = cacheService;
    }

    @Override
    public LeaderboardVO publicLeaderboard(String scope, UserPrincipal viewer, PageQueryDTO query) {
        String normalizedScope = normalizeScope(scope);

        // 1. 命中榜单缓存时直接返回，避免赛事期间高频聚合查询
        String cacheKey = CacheKeys.leaderboard(normalizedScope, query.page(), query.pageSize());
        Optional<String> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            LeaderboardVO cachedVO = JsonUtil.fromJson(cached.get(),
                    LeaderboardVO.class);
            if (cachedVO != null) {
                // isMe 与登录态相关，缓存命中时按当前请求重新计算
                return withIsMe(cachedVO, viewer, normalizedScope);
            }
        }

        // 2. 缓存缺失时执行数据库聚合
        IPage<LeaderboardRowVO> rows = queryRows(normalizedScope, null, query);
        LeaderboardVO result = assemble(rows, normalizedScope, viewer);

        // 3. 写回缓存，写缓存失败不影响主流程
        cacheService.set(cacheKey, JsonUtil.toJson(result), LEADERBOARD_TTL);
        return result;
    }

    @Override
    public PageVO<LeaderboardItemVO> adminLeaderboard(String scope, String q, PageQueryDTO query) {
        // 1. 执行带关键字筛选的聚合查询
        String normalizedScope = normalizeScope(scope);
        IPage<LeaderboardRowVO> rows = queryRows(normalizedScope, q, query);

        // 2. 转换为后台榜单条目
        List<LeaderboardItemVO> items = toItems(rows, normalizedScope, null);
        return PageUtil.toPage(rows, items);
    }

    @Override
    public TeamDetailVO teamDetail(String teamId) {
        // 1. 读取队伍
        Team team = teamMapper.selectById(teamId);
        if (team == null) {
            throw new NotFoundException("队伍不存在");
        }

        // 2. 读取队伍积分事件明细
        List<ScoreEventVO> events = collectScoreEvents(teamId, null, null);
        int score = events.stream().mapToInt(ScoreEventVO::delta).sum();
        int solvedCount = (int) events.stream()
                .filter(event -> ScoreEventType.CHALLENGE_SOLVE.apiValue().equals(event.type()))
                .count();

        // 3. 榜单排名在当前页大小范围内定位（大规模赛事建议改为专用排名查询）
        int rank = locateRank(teamId, score, solvedCount);
        return new TeamDetailVO(
                new TeamScoreVO(team.getId(), team.getName(), rank, score, solvedCount), events);
    }

    @Override
    public PageVO<ScoreEventVO> scoreEvents(String teamId, String userId, String type,
                                                            PageQueryDTO query) {
        // 1. 汇总题目得分与手工调整事件
        List<ScoreEventVO> events = collectScoreEvents(teamId, userId, type);

        // 2. 按时间倒序排列后在内存分页（事件量按队伍维度可控）
        List<ScoreEventVO> sorted = events.stream()
                .sorted(Comparator.comparing(ScoreEventVO::awardedAt).reversed())
                .toList();
        List<ScoreEventVO> items = PageUtil.slice(sorted, query);
        return new PageVO<>(items, PageUtil.of(query.page(), query.pageSize(), sorted.size()));
    }

    @Override
    @Transactional
    public ScoreEventVO adjustScore(ScoreAdjustmentDTO request,
                                                    UserPrincipal operator) {
        // 1. 校验调整主体唯一且存在
        boolean hasTeam = request.teamId() != null && !request.teamId().isBlank();
        boolean hasUser = request.userId() != null && !request.userId().isBlank();
        if (hasTeam == hasUser) {
            throw new ValidationFailedException("teamId", "必须且只能指定 teamId 或 userId 之一");
        }
        if (hasTeam && teamMapper.selectById(request.teamId()) == null) {
            throw new NotFoundException("队伍不存在");
        }
        if (hasUser && userMapper.selectById(request.userId()) == null) {
            throw new NotFoundException("账号不存在");
        }

        // 2. 校验分值与原因
        if (request.delta() == null || request.delta() == 0) {
            throw new ValidationFailedException("delta", "调整分值必须为非零整数");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ValidationFailedException("reason", "必须填写调整原因");
        }

        // 3. 落库保存调整记录，操作者与时间一并留痕
        ScoreAdjustment adjustment = new ScoreAdjustment();
        adjustment.setId(IdGenerator.next("score"));
        adjustment.setTeamId(hasTeam ? request.teamId() : null);
        adjustment.setUserId(hasUser ? request.userId() : null);
        adjustment.setDelta(request.delta());
        adjustment.setReason(request.reason().trim());
        adjustment.setAdminId(operator == null ? null : operator.userId());
        adjustment.setCreatedAt(TimeUtil.now());
        scoreAdjustmentMapper.insert(adjustment);

        // 4. 榜单缓存整体失效，保证调整结果立即生效
        cacheService.deleteByPrefix(CacheKeys.LEADERBOARD_PREFIX);

        // 5. 返回事件视图
        return new ScoreEventVO(adjustment.getId(), ScoreEventType.ADMIN_ADJUSTMENT.apiValue(),
                null, null, null, adjustment.getDelta(), adjustment.getReason(),
                operator == null ? null : operator.getUsername(), adjustment.getCreatedAt());
    }

    /** 归一化榜单维度。 */
    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SCOPE_TEAM;
        }
        String normalized = scope.trim().toLowerCase();
        if (!SCOPE_TEAM.equals(normalized) && !SCOPE_INDIVIDUAL.equals(normalized)) {
            throw new ValidationFailedException("scope", "scope 仅支持 team 或 individual");
        }
        return normalized;
    }

    /** 按维度执行聚合查询。 */
    private IPage<LeaderboardRowVO> queryRows(String scope, String keyword, PageQueryDTO query) {
        Page<LeaderboardRowVO> page = new Page<>(query.page(), query.pageSize());
        return SCOPE_INDIVIDUAL.equals(scope)
                ? solveMapper.selectIndividualLeaderboard(page, keyword)
                : solveMapper.selectTeamLeaderboard(page, keyword);
    }

    /** 组装公开榜单响应。 */
    private LeaderboardVO assemble(IPage<LeaderboardRowVO> rows, String scope, UserPrincipal viewer) {
        List<LeaderboardItemVO> items = toItems(rows, scope, viewer);
        EventConfig event = eventService.requireCurrent();
        int teams = Math.toIntExact(teamMapper.selectCount(null));
        int contestants = Math.toIntExact(userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, UserRole.CONTESTANT)));
        int solvedChallenges = distinctSolvedChallenges();
        LeaderboardStatsVO stats =
                new LeaderboardStatsVO(teams, contestants, solvedChallenges, event.getEndsAt());
        return new LeaderboardVO(TimeUtil.now(), stats, items, PageUtil.toPagination(rows));
    }

    /** 榜单条目转换，rank 由页偏移与行序推导。 */
    private List<LeaderboardItemVO> toItems(IPage<LeaderboardRowVO> rows, String scope,
                                                            UserPrincipal viewer) {
        long offset = (rows.getCurrent() - 1) * rows.getSize();
        List<LeaderboardItemVO> items = new ArrayList<>();
        int index = 0;
        for (LeaderboardRowVO row : rows.getRecords()) {
            String subjectKey = viewer == null ? null
                    : (SCOPE_INDIVIDUAL.equals(scope) ? viewer.userId() : viewer.teamId());
            boolean isMe = subjectKey != null && subjectKey.equals(row.getSubjectId());
            items.add(new LeaderboardItemVO((int) offset + index + 1,
                    SCOPE_INDIVIDUAL.equals(scope) ? null : row.getSubjectId(),
                    SCOPE_INDIVIDUAL.equals(scope) ? row.getSubjectId() : null,
                    row.getSubjectName(), row.getSolvedCount() == null ? 0 : row.getSolvedCount(),
                    row.getScore() == null ? 0 : row.getScore(), isMe, row.getLastSolvedAt()));
            index++;
        }
        return items;
    }

    /** 缓存命中时重算 isMe，避免跨账号泄漏。 */
    private LeaderboardVO withIsMe(LeaderboardVO source, UserPrincipal viewer,
                                                  String scope) {
        List<LeaderboardItemVO> items = source.items().stream()
                .map(item -> new LeaderboardItemVO(item.rank(), item.teamId(), item.userId(),
                        item.teamName(), item.solvedCount(), item.score(),
                        isMe(item, viewer, scope), item.lastSolvedAt()))
                .toList();
        return new LeaderboardVO(source.updatedAt(), source.stats(), items, source.pagination());
    }

    /** 判断榜单条目是否属于当前登录主体。 */
    private boolean isMe(LeaderboardItemVO item, UserPrincipal viewer, String scope) {
        if (viewer == null) {
            return false;
        }
        String subjectKey = SCOPE_INDIVIDUAL.equals(scope) ? viewer.userId() : viewer.teamId();
        if (subjectKey == null) {
            return false;
        }
        String itemKey = SCOPE_INDIVIDUAL.equals(scope) ? item.userId() : item.teamId();
        return subjectKey.equals(itemKey);
    }

    /** 统计已被解出的不同题目数量。 */
    private int distinctSolvedChallenges() {
        List<Object> ids = solveMapper.selectObjs(Wrappers.<Solve>query().select("DISTINCT challenge_id"));
        return ids.size();
    }

    /** 定位队伍排名，未上榜返回 0。 */
    private int locateRank(String teamId, int score, int solvedCount) {
        IPage<LeaderboardRowVO> rows = queryRows(SCOPE_TEAM, null, new PageQueryDTO(1, 100));
        long offset = 0;
        int index = 0;
        for (LeaderboardRowVO row : rows.getRecords()) {
            if (teamId.equals(row.getSubjectId())) {
                return (int) offset + index + 1;
            }
            index++;
        }
        return 0;
    }

    /** 汇总题目得分与手工调整事件。 */
    private List<ScoreEventVO> collectScoreEvents(String teamId, String userId, String type) {
        String subjectKey = teamId != null ? teamId : userId;
        List<ScoreEventVO> events = new ArrayList<>();
        if (subjectKey != null) {
            // 1. 题目得分事件
            List<Solve> solves = solveMapper.selectList(Wrappers.<Solve>lambdaQuery()
                    .eq(Solve::getSubjectKey, subjectKey));
            Map<String, Challenge> challenges = solves.isEmpty() ? Map.of()
                    : challengeMapper.selectBatchIds(solves.stream().map(Solve::getChallengeId).distinct().toList())
                    .stream().collect(Collectors.toMap(Challenge::getId, Function.identity()));
            for (Solve solve : solves) {
                Challenge challenge = challenges.get(solve.getChallengeId());
                int base = solve.getBasePoints() == null ? 0 : solve.getBasePoints();
                int bonus = solve.getBonusPoints() == null ? 0 : solve.getBonusPoints();
                events.add(new ScoreEventVO(solve.getId(),
                        ScoreEventType.CHALLENGE_SOLVE.apiValue(),
                        challenge == null ? null : challenge.getCode(), base, bonus, base + bonus, null, null,
                        solve.getSolvedAt()));
            }
        }

        // 2. 手工调整事件
        List<ScoreAdjustment> adjustments = scoreAdjustmentMapper.selectList(Wrappers.<ScoreAdjustment>lambdaQuery()
                .eq(teamId != null, ScoreAdjustment::getTeamId, teamId)
                .eq(userId != null, ScoreAdjustment::getUserId, userId));
        for (ScoreAdjustment adjustment : adjustments) {
            events.add(new ScoreEventVO(adjustment.getId(),
                    ScoreEventType.ADMIN_ADJUSTMENT.apiValue(), null, null, null, adjustment.getDelta(),
                    adjustment.getReason(), adjustment.getAdminId(), adjustment.getCreatedAt()));
        }

        // 3. 按事件类型过滤
        if (type != null && !type.isBlank()) {
            return events.stream().filter(event -> type.equalsIgnoreCase(event.type())).toList();
        }
        return events;
    }
}

package com.qaqxianyuqaq.backend.service.leaderboard.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.leaderboard.ScoreAdjustmentDTO;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.ScoreAdjustment;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import com.qaqxianyuqaq.backend.domain.entity.Submission;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.TeamMember;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.ScoreEventType;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMemberVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardItemVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardRowVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardStatsVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.ScoreEventVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.TeamDetailVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.TeamScoreVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.AdminSubmissionLogVO;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.leaderboard.ScoreAdjustmentMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.submission.SubmissionMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMemberMapper;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 赛事队伍排行榜与计分管理服务实现。
 *
 * <p>排名规则固定为：总分降序 → 解题数降序 → 达到该分数的时间升序。</p>
 */
@Service
public class LeaderboardServiceImpl implements LeaderboardService {

    /** 榜单缓存有效期，短暂缓存以削峰。 */
    private static final Duration LEADERBOARD_TTL = Duration.ofSeconds(30);

    /** 新需求只保留队伍排名维度。 */
    private static final String SCOPE_TEAM = "team";

    private final SolveMapper solveMapper;

    private final SubmissionMapper submissionMapper;

    private final ScoreAdjustmentMapper scoreAdjustmentMapper;

    private final TeamMapper teamMapper;

    private final TeamMemberMapper teamMemberMapper;

    private final UserMapper userMapper;

    private final ChallengeMapper challengeMapper;

    private final EventService eventService;

    private final TeamService teamService;

    private final CacheService cacheService;

    public LeaderboardServiceImpl(SolveMapper solveMapper, SubmissionMapper submissionMapper,
                                  ScoreAdjustmentMapper scoreAdjustmentMapper, TeamMapper teamMapper,
                                  TeamMemberMapper teamMemberMapper, UserMapper userMapper,
                                  ChallengeMapper challengeMapper, EventService eventService,
                                  TeamService teamService, CacheService cacheService) {
        this.solveMapper = solveMapper;
        this.submissionMapper = submissionMapper;
        this.scoreAdjustmentMapper = scoreAdjustmentMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.userMapper = userMapper;
        this.challengeMapper = challengeMapper;
        this.eventService = eventService;
        this.teamService = teamService;
        this.cacheService = cacheService;
    }

    @Override
    public LeaderboardVO publicLeaderboard(String scope, String eventId, UserPrincipal viewer,
                                           PageQueryDTO query) {
        // 1. 归一化赛事与队伍维度，新版本不再提供选手个人排名
        String normalizedScope = normalizeScope(scope);
        EventConfig event = resolveEvent(eventId);

        // 2. 命中赛事级缓存时直接返回，并按当前登录态重算 isMe
        String cacheKey = CacheKeys.leaderboard(event.getId(), normalizedScope,
                query.page(), query.pageSize());
        Optional<String> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            LeaderboardVO cachedVO = JsonUtil.fromJson(cached.get(), LeaderboardVO.class);
            if (cachedVO != null) {
                return withIsMe(cachedVO, viewer, event.getId(), normalizedScope);
            }
        }

        // 3. 缓存缺失时执行赛事内队伍聚合
        IPage<LeaderboardRowVO> rows = queryRows(event.getId(), normalizedScope, null, query);
        LeaderboardVO result = assemble(rows, event, normalizedScope, viewer);

        // 4. 写回赛事级缓存，写缓存失败不影响主流程
        cacheService.set(cacheKey, JsonUtil.toJson(result), LEADERBOARD_TTL);
        return result;
    }

    @Override
    public PageVO<LeaderboardItemVO> adminLeaderboard(String scope, String eventId, String q,
                                                      PageQueryDTO query) {
        // 1. 在指定赛事中执行带关键字筛选的队伍聚合
        String normalizedScope = normalizeScope(scope);
        EventConfig event = resolveEvent(eventId);
        IPage<LeaderboardRowVO> rows = queryRows(event.getId(), normalizedScope, q, query);

        // 2. 转换为后台队伍榜单条目
        return PageUtil.toPage(rows, toItems(rows, normalizedScope, event.getId(), null));
    }

    @Override
    public TeamDetailVO teamDetail(String eventId, String teamId, PageQueryDTO query) {
        // 1. 校验队伍属于指定赛事
        EventConfig event = resolveEvent(eventId);
        Team team = teamService.requireEventTeam(event.getId(), teamId);

        // 2. 装载全部队伍成员及成员账号信息
        List<TeamMember> memberships = teamService.members(team.getId());
        Map<String, User> users = loadUsers(memberships.stream().map(TeamMember::getUserId).toList());
        List<TeamMemberVO> members = memberships.stream()
                .map(membership -> toMemberVO(membership, users.get(membership.getUserId()), team))
                .toList();

        // 3. 分页查询该队伍全部成员产生的提交日志
        IPage<Submission> submissionPage = submissionMapper.selectPage(
                new Page<>(query.page(), query.pageSize()),
                Wrappers.<Submission>lambdaQuery()
                        .eq(Submission::getTeamId, team.getId())
                        .orderByDesc(Submission::getSubmittedAt));
        Map<String, Challenge> challenges = loadChallenges(
                submissionPage.getRecords().stream().map(Submission::getChallengeId).toList());
        List<AdminSubmissionLogVO> logs = submissionPage.getRecords().stream()
                .map(submission -> toSubmissionLog(submission, challenges.get(submission.getChallengeId()),
                        users.get(submission.getUserId())))
                .toList();

        // 4. 汇总该赛事积分事件并计算排名
        List<ScoreEventVO> events = collectScoreEvents(event.getId(), team.getId(), null, null);
        int score = events.stream().mapToInt(ScoreEventVO::delta).sum();
        int solvedCount = (int) events.stream()
                .filter(eventItem -> ScoreEventType.CHALLENGE_SOLVE.apiValue().equals(eventItem.type()))
                .count();
        int rank = locateRank(event.getId(), team.getId());
        TeamScoreVO teamScore = new TeamScoreVO(team.getId(), team.getName(), rank, score, solvedCount);
        return new TeamDetailVO(teamScore, members,
                PageUtil.toPage(submissionPage, logs), events);
    }

    @Override
    public PageVO<ScoreEventVO> scoreEvents(String eventId, String teamId, String userId,
                                            String type, PageQueryDTO query) {
        // 1. 校验查询主体必须且只能指定队伍或账号
        boolean hasTeam = teamId != null && !teamId.isBlank();
        boolean hasUser = userId != null && !userId.isBlank();
        if (hasTeam == hasUser) {
            throw new ValidationFailedException("teamId", "必须且只能指定 teamId 或 userId 之一");
        }
        EventConfig event = resolveEvent(eventId);
        if (hasTeam) {
            teamService.requireEventTeam(event.getId(), teamId);
        }

        // 2. 汇总赛事内题目得分与手工调整事件
        List<ScoreEventVO> events = collectScoreEvents(event.getId(), teamId, userId, type);

        // 3. 按时间倒序后在内存分页
        List<ScoreEventVO> sorted = events.stream()
                .sorted(Comparator.comparing(ScoreEventVO::awardedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<ScoreEventVO> items = PageUtil.slice(sorted, query);
        return new PageVO<>(items, PageUtil.of(query.page(), query.pageSize(), sorted.size()));
    }

    @Override
    @Transactional
    public ScoreEventVO adjustScore(ScoreAdjustmentDTO request, UserPrincipal operator) {
        // 1. 新需求只允许按队伍调整积分
        if (request.userId() != null && !request.userId().isBlank()) {
            throw new ValidationFailedException("userId", "当前赛事只支持按队伍调整积分");
        }
        if (request.teamId() == null || request.teamId().isBlank()) {
            throw new ValidationFailedException("teamId", "必须指定队伍");
        }
        Team team = teamMapper.selectById(request.teamId());
        if (team == null) {
            throw new NotFoundException("队伍不存在");
        }

        // 2. 校验分值与原因
        if (request.delta() == null || request.delta() == 0) {
            throw new ValidationFailedException("delta", "调整分值必须为非零整数");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ValidationFailedException("reason", "必须填写调整原因");
        }

        // 3. 落库保存队伍调整记录，操作者与时间一并留痕
        ScoreAdjustment adjustment = new ScoreAdjustment();
        adjustment.setId(IdGenerator.next("score"));
        adjustment.setTeamId(team.getId());
        adjustment.setUserId(null);
        adjustment.setDelta(request.delta());
        adjustment.setReason(request.reason().trim());
        adjustment.setAdminId(operator == null ? null : operator.userId());
        adjustment.setCreatedAt(TimeUtil.now());
        scoreAdjustmentMapper.insert(adjustment);

        // 4. 榜单缓存整体失效，保证队伍分数立即生效
        cacheService.deleteByPrefix(CacheKeys.LEADERBOARD_PREFIX);

        // 5. 返回队伍积分事件
        return new ScoreEventVO(adjustment.getId(), ScoreEventType.ADMIN_ADJUSTMENT.apiValue(),
                null, null, null, adjustment.getDelta(), adjustment.getReason(),
                operator == null ? null : operator.getUsername(), adjustment.getCreatedAt());
    }

    /** 解析赛事；未指定时使用当前赛事。 */
    private EventConfig resolveEvent(String eventId) {
        return eventId == null || eventId.isBlank()
                ? eventService.requireCurrent() : eventService.requireById(eventId);
    }

    /** 归一化榜单维度，仅允许队伍排名。 */
    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SCOPE_TEAM;
        }
        String normalized = scope.trim().toLowerCase();
        if (!SCOPE_TEAM.equals(normalized)) {
            throw new ValidationFailedException("scope", "新赛事规则仅支持 scope=team");
        }
        return normalized;
    }

    /** 执行赛事内队伍聚合查询。 */
    private IPage<LeaderboardRowVO> queryRows(String eventId, String scope, String keyword,
                                              PageQueryDTO query) {
        if (!SCOPE_TEAM.equals(scope)) {
            throw new ValidationFailedException("scope", "仅支持队伍排名");
        }
        return solveMapper.selectTeamLeaderboard(new Page<>(query.page(), query.pageSize()),
                eventId, keyword);
    }

    /** 组装公开榜单响应。 */
    private LeaderboardVO assemble(IPage<LeaderboardRowVO> rows, EventConfig event,
                                   String scope, UserPrincipal viewer) {
        List<LeaderboardItemVO> items = toItems(rows, scope, event.getId(), viewer);
        int teams = Math.toIntExact(teamMapper.selectCount(Wrappers.<Team>lambdaQuery()
                .eq(Team::getEventId, event.getId())));
        int contestants = Math.toIntExact(teamMemberMapper.selectCount(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getEventId, event.getId())));
        int solvedChallenges = distinctSolvedChallenges(event.getId());
        LeaderboardStatsVO stats =
                new LeaderboardStatsVO(teams, contestants, solvedChallenges, event.getEndsAt());
        return new LeaderboardVO(TimeUtil.now(), stats, items, PageUtil.toPagination(rows));
    }

    /** 榜单条目转换，rank 由页偏移与行序推导。 */
    private List<LeaderboardItemVO> toItems(IPage<LeaderboardRowVO> rows, String scope,
                                            String eventId, UserPrincipal viewer) {
        Team viewerTeam = viewer == null ? null : teamService.findMembership(eventId, viewer.userId());
        long offset = (rows.getCurrent() - 1) * rows.getSize();
        List<LeaderboardItemVO> items = new ArrayList<>();
        int index = 0;
        for (LeaderboardRowVO row : rows.getRecords()) {
            boolean isMe = viewerTeam != null && viewerTeam.getId().equals(row.getSubjectId());
            items.add(new LeaderboardItemVO((int) offset + index + 1, row.getSubjectId(), null,
                    row.getSubjectName(), row.getSolvedCount() == null ? 0 : row.getSolvedCount(),
                    row.getScore() == null ? 0 : row.getScore(), isMe, row.getLastSolvedAt()));
            index++;
        }
        return items;
    }

    /** 缓存命中时按当前登录队伍重算 isMe，避免跨账号泄漏。 */
    private LeaderboardVO withIsMe(LeaderboardVO source, UserPrincipal viewer,
                                   String eventId, String scope) {
        Team viewerTeam = viewer == null ? null : teamService.findMembership(eventId, viewer.userId());
        List<LeaderboardItemVO> items = source.items().stream()
                .map(item -> new LeaderboardItemVO(item.rank(), item.teamId(), item.userId(),
                        item.teamName(), item.solvedCount(), item.score(),
                        viewerTeam != null && viewerTeam.getId().equals(item.teamId()),
                        item.lastSolvedAt()))
                .toList();
        return new LeaderboardVO(source.updatedAt(), source.stats(), items, source.pagination());
    }

    /** 统计赛事内已被不同队伍解出的题目数量。 */
    private int distinctSolvedChallenges(String eventId) {
        List<String> challengeIds = challengeMapper.selectList(Wrappers.<Challenge>lambdaQuery()
                        .eq(Challenge::getEventId, eventId))
                .stream().map(Challenge::getId).toList();
        if (challengeIds.isEmpty()) {
            return 0;
        }
        return Math.toIntExact(solveMapper.selectList(Wrappers.<Solve>lambdaQuery()
                        .in(Solve::getChallengeId, challengeIds))
                .stream().map(Solve::getChallengeId).distinct().count());
    }

    /** 在赛事队伍榜中定位排名。 */
    private int locateRank(String eventId, String teamId) {
        IPage<LeaderboardRowVO> rows = solveMapper.selectTeamLeaderboard(
                new Page<>(1, 1000), eventId, null);
        int index = 1;
        for (LeaderboardRowVO row : rows.getRecords()) {
            if (teamId.equals(row.getSubjectId())) {
                return index;
            }
            index++;
        }
        return 0;
    }

    /** 汇总赛事内题目得分与手工调整事件。 */
    private List<ScoreEventVO> collectScoreEvents(String eventId, String teamId,
                                                  String userId, String type) {
        String subjectKey = teamId != null ? teamId : userId;
        List<ScoreEventVO> events = new ArrayList<>();
        if (subjectKey != null) {
            // 1. 只装载赛事题目对应的解题得分事件
            List<String> challengeIds = challengeMapper.selectList(Wrappers.<Challenge>lambdaQuery()
                            .eq(Challenge::getEventId, eventId))
                    .stream().map(Challenge::getId).toList();
            List<Solve> solves = challengeIds.isEmpty() ? List.of()
                    : solveMapper.selectList(Wrappers.<Solve>lambdaQuery()
                    .eq(Solve::getSubjectKey, subjectKey)
                    .in(Solve::getChallengeId, challengeIds));
            Map<String, Challenge> challenges = loadChallenges(
                    solves.stream().map(Solve::getChallengeId).toList());
            for (Solve solve : solves) {
                Challenge challenge = challenges.get(solve.getChallengeId());
                int base = solve.getBasePoints() == null ? 0 : solve.getBasePoints();
                int bonus = solve.getBonusPoints() == null ? 0 : solve.getBonusPoints();
                events.add(new ScoreEventVO(solve.getId(), ScoreEventType.CHALLENGE_SOLVE.apiValue(),
                        challenge == null ? null : challenge.getCode(), base, bonus, base + bonus,
                        null, null, solve.getSolvedAt()));
            }
        }

        // 2. 装载手工调整事件；队伍维度天然通过 teams.event_id 限定赛事
        List<ScoreAdjustment> adjustments = scoreAdjustmentMapper.selectList(
                Wrappers.<ScoreAdjustment>lambdaQuery()
                        .eq(teamId != null, ScoreAdjustment::getTeamId, teamId)
                        .eq(userId != null, ScoreAdjustment::getUserId, userId));
        for (ScoreAdjustment adjustment : adjustments) {
            events.add(new ScoreEventVO(adjustment.getId(),
                    ScoreEventType.ADMIN_ADJUSTMENT.apiValue(), null, null, null,
                    adjustment.getDelta(), adjustment.getReason(), adjustment.getAdminId(),
                    adjustment.getCreatedAt()));
        }

        // 3. 按事件类型过滤
        if (type != null && !type.isBlank()) {
            return events.stream().filter(event -> type.equalsIgnoreCase(event.type())).toList();
        }
        return events;
    }

    /** 批量装载账号。 */
    private Map<String, User> loadUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** 批量装载题目。 */
    private Map<String, Challenge> loadChallenges(List<String> challengeIds) {
        if (challengeIds == null || challengeIds.isEmpty()) {
            return Map.of();
        }
        return challengeMapper.selectBatchIds(challengeIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Challenge::getId, Function.identity()));
    }

    /** 组装后台成员视图。 */
    private TeamMemberVO toMemberVO(TeamMember membership, User user, Team team) {
        return new TeamMemberVO(membership.getUserId(), user == null ? null : user.getUsername(),
                user == null ? null : user.getNickname(),
                membership.getUserId().equals(team.getCreatedBy()), membership.getJoinedAt());
    }

    /** 组装后台提交日志。 */
    private AdminSubmissionLogVO toSubmissionLog(Submission submission, Challenge challenge, User user) {
        return new AdminSubmissionLogVO(submission.getId(), submission.getUserId(),
                user == null ? null : user.getUsername(),
                challenge == null ? null : challenge.getCode(),
                challenge == null ? null : challenge.getTitle(),
                submission.getResult().apiValue(), submission.getPointsAwarded(), submission.getSolvePlace(),
                submission.getSource() == null ? null : submission.getSource().apiValue(),
                submission.getSubmittedAt());
    }
}

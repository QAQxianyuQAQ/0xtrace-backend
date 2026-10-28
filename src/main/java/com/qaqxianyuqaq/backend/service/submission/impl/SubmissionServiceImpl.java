package com.qaqxianyuqaq.backend.service.submission.impl;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.submission.SubmitAnswerDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.MyProgressVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.ProgressVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionVO;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import com.qaqxianyuqaq.backend.domain.entity.Submission;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeRuntimeType;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.domain.enums.SolveSource;
import com.qaqxianyuqaq.backend.domain.enums.SubmissionResult;
import com.qaqxianyuqaq.backend.aspect.Idempotent;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.ForbiddenException;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.FlagOwnerException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.submission.SubmissionMapper;
import com.qaqxianyuqaq.backend.security.CredentialCipher;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeContainerService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.service.submission.ScoringService;
import com.qaqxianyuqaq.backend.service.submission.SubmissionService;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.util.DigestUtil;
import com.qaqxianyuqaq.backend.util.PageUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 答案提交服务实现。 */
@Service
public class SubmissionServiceImpl implements SubmissionService {

    private final ChallengeService challengeService;

    private final EventService eventService;

    private final ScoringService scoringService;

    private final TeamService teamService;

    private final SubmissionMapper submissionMapper;

    private final SolveMapper solveMapper;

    private final ChallengeMapper challengeMapper;

    private final CredentialCipher credentialCipher;

    private final PermissionAssert permissionAssert;

    private final ChallengeContainerService challengeContainerService;

    public SubmissionServiceImpl(ChallengeService challengeService, EventService eventService,
                                 ScoringService scoringService, TeamService teamService,
                                 SubmissionMapper submissionMapper, SolveMapper solveMapper,
                                 ChallengeMapper challengeMapper, CredentialCipher credentialCipher,
                                 PermissionAssert permissionAssert,
                                 ChallengeContainerService challengeContainerService) {
        this.challengeService = challengeService;
        this.eventService = eventService;
        this.scoringService = scoringService;
        this.teamService = teamService;
        this.submissionMapper = submissionMapper;
        this.solveMapper = solveMapper;
        this.challengeMapper = challengeMapper;
        this.credentialCipher = credentialCipher;
        this.permissionAssert = permissionAssert;
        this.challengeContainerService = challengeContainerService;
    }

    @Override
    @Transactional
    @RateLimit(scope = "submission")
    @Idempotent(scope = "submission")
    public SubmissionResultVO submit(String code, SubmitAnswerDTO request,
                                                    UserPrincipal principal) {
        // 1. 校验操作者具备参赛权限
        permissionAssert.assertContestant(principal);

        // 2. 读取题目并校验当前是否允许作答
        Challenge challenge = challengeService.requireByCode(code);
        assertAnswerable(challenge);

        // 3. 校验题目所属赛事处于开放状态
        assertEventOpen(challenge);

        // 4. 容器题目必须提交当前队伍容器实际获得的 Flag
        validateContainerFlag(challenge, request.answer(), principal);

        // 5. 同一 requestId 重放时返回既有提交结果，避免重复计分
        SubmissionResultVO replayed = replayByRequestId(request, principal, challenge);
        if (replayed != null) {
            return replayed;
        }

        // 6. 服务端权威判题：容器 Flag 已通过归属校验，手工题目比较加密标准答案
        boolean correct = judge(challenge, request.answer());

        // 7. 交由计分引擎完成名次、加分与落库
        return scoringService.award(principal, challenge, SolveSource.ANSWER, request.answer(),
                request.requestId(), correct);
    }

    @Override
    public SubmissionVO getSubmission(String submissionId, UserPrincipal principal) {
        // 1. 读取提交记录
        Submission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw new NotFoundException("提交记录不存在");
        }

        // 2. 仅允许本人或本队查看，避免越权读取他人提交
        assertOwnership(submission, principal);

        // 3. 组装响应
        Challenge challenge = challengeMapper.selectById(submission.getChallengeId());
        return toVO(submission, challenge);
    }

    @Override
    public PageVO<SubmissionVO> mySubmissions(UserPrincipal principal, String eventId,
                                              String challengeCode, String result, PageQueryDTO query) {
        // 1. 解析指定赛事并读取选手在该赛事中的队伍
        EventConfig event = eventId == null || eventId.isBlank()
                ? eventService.requireCurrent() : eventService.requireById(eventId);
        Team team = teamService.findMembership(event.getId(), principal.userId());

        // 2. 组装本人在该赛事的历史提交与当前队伍提交过滤条件
        var wrapper = Wrappers.<Submission>lambdaQuery()
                .and(condition -> condition.eq(Submission::getUserId, principal.userId())
                        .or(team != null, part -> part.eq(Submission::getTeamId,
                                team == null ? null : team.getId())))
                .orderByDesc(Submission::getSubmittedAt);

        // 3. 题目编号过滤：编号不存在或不属于该赛事时直接返回空页
        if (challengeCode != null && !challengeCode.isBlank()) {
            Challenge challenge = challengeMapper.selectOne(Wrappers.<Challenge>lambdaQuery()
                    .eq(Challenge::getCode, challengeCode)
                    .eq(Challenge::getEventId, event.getId()));
            if (challenge == null) {
                return PageUtil.toPage(new Page<>(query.page(), query.pageSize()), List.of());
            }
            wrapper.eq(Submission::getChallengeId, challenge.getId());
        } else {
            // 4. 默认只返回指定赛事题目对应的提交记录
            List<String> eventChallengeIds = challengeMapper.selectList(Wrappers.<Challenge>lambdaQuery()
                            .eq(Challenge::getEventId, event.getId()))
                    .stream().map(Challenge::getId).toList();
            if (eventChallengeIds.isEmpty()) {
                return PageUtil.toPage(new Page<>(query.page(), query.pageSize()), List.of());
            }
            wrapper.in(Submission::getChallengeId, eventChallengeIds);
        }

        // 5. 结果过滤（correct/incorrect）
        if (result != null && !result.isBlank()) {
            wrapper.eq(Submission::getResult,
                    "correct".equalsIgnoreCase(result) ? SubmissionResult.CORRECT : SubmissionResult.INCORRECT);
        }

        // 6. 分页查询并批量装配题目信息
        IPage<Submission> page = submissionMapper.selectPage(new Page<>(query.page(), query.pageSize()), wrapper);
        Map<String, Challenge> challenges = challengeMapper.selectBatchIds(
                        page.getRecords().stream().map(Submission::getChallengeId).distinct().toList())
                .stream().collect(Collectors.toMap(Challenge::getId, Function.identity()));
        List<SubmissionVO> items = page.getRecords().stream()
                .map(submission -> toVO(submission, challenges.get(submission.getChallengeId())))
                .toList();
        return PageUtil.toPage(page, items);
    }

    @Override
    public MyProgressVO myProgress(UserPrincipal principal, String eventId) {
        // 1. 解析赛事并读取选手在该赛事中的队伍
        EventConfig event = eventId == null || eventId.isBlank()
                ? eventService.requireCurrent() : eventService.requireById(eventId);
        Team team = teamService.findMembership(event.getId(), principal.userId());
        if (team == null) {
            int total = Math.toIntExact(challengeMapper.selectCount(Wrappers.<Challenge>lambdaQuery()
                    .eq(Challenge::getStatus, ChallengeStatus.PUBLISHED)
                    .eq(Challenge::getEventId, event.getId())));
            return new MyProgressVO(0, total, 0, null, null, List.of());
        }

        // 2. 统计该赛事队伍已解出题目
        List<Solve> solves = solveMapper.selectList(Wrappers.<Solve>lambdaQuery()
                .eq(Solve::getSubjectKey, team.getId())
                .orderByAsc(Solve::getSolvedAt));

        // 3. 汇总累计分数
        int score = solves.stream()
                .mapToInt(solve -> (solve.getBasePoints() == null ? 0 : solve.getBasePoints())
                        + (solve.getBonusPoints() == null ? 0 : solve.getBonusPoints()))
                .sum();

        // 4. 统计该赛事已发布题目总数，形成 x/y 进度
        long total = challengeMapper.selectCount(Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getStatus, ChallengeStatus.PUBLISHED)
                .eq(Challenge::getEventId, event.getId()));
        Map<String, String> codeById = challengeMapper.selectBatchIds(
                        solves.stream().map(Solve::getChallengeId).distinct().toList())
                .stream().collect(Collectors.toMap(Challenge::getId, Challenge::getCode));
        List<String> solvedCodes = solves.stream()
                .map(solve -> codeById.get(solve.getChallengeId()))
                .filter(java.util.Objects::nonNull)
                .toList();

        // 5. 组装响应，含赛事队伍信息与解题编号列表
        return new MyProgressVO(solves.size(), (int) total, score, team.getId(),
                team.getName(), solvedCodes);
    }

    /** 校验题目当前可作答。 */
    private void assertAnswerable(Challenge challenge) {
        if (challenge.getStatus() == ChallengeStatus.DRAFT || challenge.getStatus() == ChallengeStatus.ARCHIVED) {
            throw new NotFoundException(ErrorCode.CHALLENGE_NOT_FOUND, "题目不存在或不可见");
        }
        if (!challenge.getStatus().answerable()) {
            throw new ConflictException(ErrorCode.CHALLENGE_NOT_ANSWERABLE, "题目当前不可作答");
        }
    }

    /** 校验赛事处于开放且未结束状态。 */
    private void assertEventOpen(Challenge challenge) {
        EventConfig event = challenge.getEventId() == null
                ? eventService.requireCurrent() : eventService.requireById(challenge.getEventId());
        if (event.getStatus() != EventStatus.OPEN) {
            throw new ConflictException(ErrorCode.SUBMISSION_CLOSED, "赛事当前不可作答");
        }
        LocalDateTime now = TimeUtil.now();
        if (event.getStartsAt() != null && now.isBefore(event.getStartsAt())) {
            throw new ConflictException(ErrorCode.SUBMISSION_CLOSED, "赛事尚未开始");
        }
        if (event.getEndsAt() != null && now.isAfter(event.getEndsAt())) {
            throw new ConflictException(ErrorCode.SUBMISSION_CLOSED, "赛事已结束");
        }
    }

    /** 按 requestId 幂等回放既有提交。 */
    private SubmissionResultVO replayByRequestId(SubmitAnswerDTO request,
                                                 UserPrincipal principal, Challenge challenge) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            return null;
        }
        Submission existing = submissionMapper.selectOne(Wrappers.<Submission>lambdaQuery()
                .eq(Submission::getRequestId, request.requestId())
                .eq(Submission::getUserId, principal.userId())
                .orderByDesc(Submission::getSubmittedAt)
                .last("LIMIT 1"));
        if (existing == null) {
            return null;
        }
        String eventId = challenge.getEventId() == null
                ? eventService.requireCurrent().getId() : challenge.getEventId();
        Team team = teamService.findMembership(eventId, principal.userId());
        String subjectKey = team == null ? principal.userId() : team.getId();
        // 既有记录为正确提交时，回放首次完成信息（名次、得分）
        if (existing.getResult() == SubmissionResult.CORRECT) {
            Solve solve = solveMapper.selectOne(Wrappers.<Solve>lambdaQuery()
                    .eq(Solve::getSubjectKey, subjectKey)
                    .eq(Solve::getChallengeId, challenge.getId()));
            if (solve != null) {
                return new SubmissionResultVO(existing.getId(), challenge.getCode(),
                        SubmissionResult.CORRECT.apiValue(), "already_solved", solve.getSolvedAt(),
                        solve.getBasePoints(), solve.getBonusPoints(),
                        (solve.getBasePoints() == null ? 0 : solve.getBasePoints())
                                + (solve.getBonusPoints() == null ? 0 : solve.getBonusPoints()),
                        solve.getSolvePlace(), progressOf(eventId, subjectKey), solve.getSource() == null ? null
                        : solve.getSource().apiValue());
            }
        }
        return new SubmissionResultVO(existing.getId(), challenge.getCode(),
                existing.getResult().apiValue(), null, null, 0, 0, 0, null,
                progressOf(eventId, subjectKey),
                SolveSource.ANSWER.apiValue());
    }

    /** 统计进度，用于幂等回放响应。 */
    private ProgressVO progressOf(String eventId, String subjectKey) {
        long solved = solveMapper.selectCount(Wrappers.<Solve>lambdaQuery()
                .eq(Solve::getSubjectKey, subjectKey));
        long total = challengeMapper.selectCount(Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getStatus, ChallengeStatus.PUBLISHED)
                .eq(Challenge::getEventId, eventId));
        return new ProgressVO((int) solved, (int) total);
    }

    /** 服务端判题：比对提交摘要与标准答案。 */
    private boolean judge(Challenge challenge, String answer) {
        if (challenge.getRuntimeType() == ChallengeRuntimeType.DOCKER) {
            return true;
        }
        String standard = credentialCipher.decrypt(challenge.getAnswerSecret());
        if (standard == null || answer == null) {
            return false;
        }
        return DigestUtil.constantTimeEquals(standard.trim(), answer.trim());
    }

    /** 校验容器 Flag 属于当前用户所在队伍，并记录疑似串通行为。 */
    private void validateContainerFlag(Challenge challenge, String answer, UserPrincipal principal) {
        if (challenge.getRuntimeType() != ChallengeRuntimeType.DOCKER) {
            return;
        }
        String eventId = challenge.getEventId();
        Team team = teamService.requireMembership(eventId, principal.userId());
        ChallengeContainerInstance instance = challengeContainerService.requireReady(challenge, team);
        String expected = credentialCipher.decrypt(instance.getFlagSecret());
        if (expected == null || !DigestUtil.constantTimeEquals(expected.trim(), answer.trim())) {
            throw new FlagOwnerException(team.getName(), principal.getUsername());
        }
    }

    /** 校验提交记录归属。 */
    private void assertOwnership(Submission submission, UserPrincipal principal) {
        boolean isSelf = principal.userId().equals(submission.getUserId());
        boolean isTeammate = submission.getTeamId() != null
                && teamService.membershipTeamIds(principal.userId()).contains(submission.getTeamId());
        if (!isSelf && !isTeammate && !principal.isAdmin()) {
            throw new ForbiddenException("无权查看他人提交记录");
        }
    }

    /** 提交实体转响应模型。 */
    private SubmissionVO toVO(Submission submission, Challenge challenge) {
        return new SubmissionVO(submission.getId(),
                challenge == null ? null : challenge.getCode(),
                challenge == null ? null : challenge.getTitle(),
                submission.getResult().apiValue(), submission.getPointsAwarded(), submission.getSolvePlace(),
                submission.getSource() == null ? null : submission.getSource().apiValue(),
                submission.getSubmittedAt());
    }
}

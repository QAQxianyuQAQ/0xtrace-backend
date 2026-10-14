package com.qaqxianyuqaq.backend.service.submission.impl;

import com.qaqxianyuqaq.backend.domain.vo.submission.ProgressVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.aspect.DistributedLock;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import com.qaqxianyuqaq.backend.domain.entity.Submission;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.CompletionType;
import com.qaqxianyuqaq.backend.domain.enums.SolveSource;
import com.qaqxianyuqaq.backend.domain.enums.SubmissionResult;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.submission.SubmissionMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.service.submission.ScoringService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.DigestUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计分引擎实现。
 *
 * <p>首解名次、额外加分与重复提交的幂等语义全部在此收口，客户端传入的分数与名次一律不采信。</p>
 */
@Service
public class ScoringServiceImpl implements ScoringService {

    private final SolveMapper solveMapper;

    private final SubmissionMapper submissionMapper;

    private final ChallengeMapper challengeMapper;

    private final CacheService cacheService;

    public ScoringServiceImpl(SolveMapper solveMapper, SubmissionMapper submissionMapper,
                              ChallengeMapper challengeMapper, CacheService cacheService) {
        this.solveMapper = solveMapper;
        this.submissionMapper = submissionMapper;
        this.challengeMapper = challengeMapper;
        this.cacheService = cacheService;
    }

    @Override
    public String myStatus(UserPrincipal viewer, Challenge challenge) {
        if (viewer == null) {
            return "unsolved";
        }
        // 有队伍时以队伍维度判定，否则以个人维度判定
        Solve solve = findSolve(subjectKeyOf(viewer), challenge.getId());
        return solve == null ? "unsolved" : "solved";
    }

    @Override
    @Transactional
    @DistributedLock(key = "#principal.userId() + ':' + #challenge.id")
    public SubmissionResultVO award(UserPrincipal principal, Challenge challenge, SolveSource source,
                                                   String answerText, String requestId, boolean correct) {
        // 1. 计算统计主体键：有队伍按队伍，无队伍按个人
        String subjectKey = subjectKeyOf(principal);

        // 2. 已完成题目直接返回首次完成数据，不重复计分、不新增解题记录
        Solve existing = findSolve(subjectKey, challenge.getId());
        if (existing != null) {
            return alreadySolvedResponse(principal, challenge, existing);
        }

        // 3. 无论对错都留存提交记录，便于赛事复盘与限次策略
        Submission submission = insertSubmission(principal, challenge, source, answerText, requestId, correct);

        // 4. 答案错误时直接返回错误结果，不产生积分
        if (!correct) {
            return new SubmissionResultVO(submission.getId(), challenge.getCode(),
                    SubmissionResult.INCORRECT.apiValue(), null, null, 0, 0, 0, null,
                    progress(principal), source.apiValue());
        }

        // 5. 以数据库既有解题数推导名次，并发场景由唯一约束兜底
        long solvedBefore = solveMapper.selectCount(Wrappers.<Solve>lambdaQuery()
                .eq(Solve::getChallengeId, challenge.getId()));
        int place = Long.valueOf(solvedBefore).intValue() + 1;
        int basePoints = challenge.getPoints() == null ? 0 : challenge.getPoints();
        int bonusPoints = resolveBonus(challenge, place);

        // 6. 写入解题记录，命中唯一约束说明并发下已由他人先解出
        Solve solve = new Solve();
        solve.setId(IdGenerator.next("solve"));
        solve.setUserId(principal.userId());
        solve.setTeamId(principal.teamId());
        solve.setSubjectKey(subjectKey);
        solve.setChallengeId(challenge.getId());
        solve.setSubmissionId(submission.getId());
        solve.setSolvedAt(submission.getSubmittedAt());
        solve.setSolvePlace(place);
        solve.setBasePoints(basePoints);
        solve.setBonusPoints(bonusPoints);
        solve.setSource(source);
        solve.setIdempotencyKey(requestId);
        try {
            solveMapper.insert(solve);
        } catch (DuplicateKeyException exception) {
            Solve concurrent = findSolve(subjectKey, challenge.getId());
            if (concurrent != null) {
                return alreadySolvedResponse(principal, challenge, concurrent);
            }
            throw exception;
        }

        // 7. 回填提交记录的实际得分与名次
        submission.setPointsAwarded(basePoints + bonusPoints);
        submission.setSolvePlace(place);
        submissionMapper.updateById(submission);

        // 8. 榜单缓存失效，保证榜单在 30 秒内反映最新分数
        cacheService.deleteByPrefix(CacheKeys.LEADERBOARD_PREFIX);

        // 9. 返回首解结果，客户端据此展示恭喜完成与实际得分
        return new SubmissionResultVO(submission.getId(), challenge.getCode(),
                SubmissionResult.CORRECT.apiValue(), CompletionType.FIRST_SOLVE.apiValue(), solve.getSolvedAt(),
                basePoints, bonusPoints, basePoints + bonusPoints, place, progress(principal), source.apiValue());
    }

    /** 按名次计算额外加分。 */
    private int resolveBonus(Challenge challenge, int place) {
        return switch (place) {
            case 1 -> nullToZero(challenge.getBonusFirst());
            case 2 -> nullToZero(challenge.getBonusSecond());
            case 3 -> nullToZero(challenge.getBonusThird());
            default -> nullToZero(challenge.getBonusOther());
        };
    }

    /** 空值按 0 处理。 */
    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 组装尚未完成题目的提交记录。 */
    private Submission insertSubmission(UserPrincipal principal, Challenge challenge, SolveSource source,
                                        String answerText, String requestId, boolean correct) {
        Submission submission = new Submission();
        submission.setId(IdGenerator.next("sub"));
        submission.setUserId(principal.userId());
        submission.setTeamId(principal.teamId());
        submission.setChallengeId(challenge.getId());
        submission.setAnswerDigest(DigestUtil.sha256Hex(answerText == null ? "" : answerText));
        submission.setResult(correct ? SubmissionResult.CORRECT : SubmissionResult.INCORRECT);
        submission.setPointsAwarded(0);
        submission.setSource(source);
        submission.setRequestId(requestId);
        submission.setSubmittedAt(TimeUtil.now());
        submissionMapper.insert(submission);
        return submission;
    }

    /** 组装已完成题目的幂等响应。 */
    private SubmissionResultVO alreadySolvedResponse(UserPrincipal principal, Challenge challenge,
                                                                   Solve solve) {
        int points = nullToZero(solve.getBasePoints()) + nullToZero(solve.getBonusPoints());
        return new SubmissionResultVO(solve.getSubmissionId(), challenge.getCode(),
                SubmissionResult.CORRECT.apiValue(), CompletionType.ALREADY_SOLVED.apiValue(), solve.getSolvedAt(),
                solve.getBasePoints(), solve.getBonusPoints(), points, solve.getSolvePlace(),
                progress(principal), solve.getSource() == null ? null : solve.getSource().apiValue());
    }

    /** 统计当前主体与全站的解题进度。 */
    private ProgressVO progress(UserPrincipal principal) {
        long solved = solveMapper.selectCount(Wrappers.<Solve>lambdaQuery()
                .eq(Solve::getSubjectKey, subjectKeyOf(principal)));
        long total = challengeMapper.selectCount(Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getStatus, ChallengeStatus.PUBLISHED));
        return new ProgressVO((int) solved, (int) total);
    }

    /** 查询主体在某题目上的解题记录。 */
    private Solve findSolve(String subjectKey, String challengeId) {
        return solveMapper.selectOne(Wrappers.<Solve>lambdaQuery()
                .eq(Solve::getSubjectKey, subjectKey)
                .eq(Solve::getChallengeId, challengeId));
    }

    /** 计算统计主体键。 */
    private String subjectKeyOf(UserPrincipal principal) {
        return principal.teamId() == null ? principal.userId() : principal.teamId();
    }
}

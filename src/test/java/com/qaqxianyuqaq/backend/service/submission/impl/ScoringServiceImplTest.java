package com.qaqxianyuqaq.backend.service.submission.impl;

import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import com.qaqxianyuqaq.backend.domain.enums.SolveSource;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.submission.SubmissionMapper;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 计分引擎单元测试：覆盖首解加分、重复提交与错误答案三条核心路径。 */
@ExtendWith(MockitoExtension.class)
class ScoringServiceImplTest {

    @Mock
    private SolveMapper solveMapper;

    @Mock
    private SubmissionMapper submissionMapper;

    @Mock
    private ChallengeMapper challengeMapper;

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private ScoringServiceImpl scoringService;

    private UserPrincipal principal;

    private Challenge challenge;

    @BeforeEach
    void setUp() {
        principal = new UserPrincipal("usr_1", "field_129", com.qaqxianyuqaq.backend.domain.enums.UserRole.CONTESTANT,
                "team_1", 0);
        challenge = new Challenge();
        challenge.setId("chal_1");
        challenge.setCode("GEO-01");
        challenge.setPoints(400);
        challenge.setBonusFirst(100);
        challenge.setBonusSecond(60);
        challenge.setBonusThird(30);
        challenge.setBonusOther(0);
    }

    @Test
    @DisplayName("首解正确时按基础分与首解加分合计计分")
    void shouldAwardFirstSolveBonus() {
        when(solveMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(solveMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 1L);
        when(challengeMapper.selectCount(any(Wrapper.class))).thenReturn(12L);

        SubmissionResultVO result = scoringService.award(principal, challenge,
                SolveSource.ANSWER, "flag{ok}", "req-1", true);

        assertThat(result.completion()).isEqualTo("first_solve");
        assertThat(result.basePoints()).isEqualTo(400);
        assertThat(result.bonusAwarded()).isEqualTo(100);
        assertThat(result.pointsAwarded()).isEqualTo(500);
        assertThat(result.solvePlace()).isEqualTo(1);
        assertThat(result.progress().total()).isEqualTo(12);
        verify(solveMapper).insert(any(Solve.class));
        verify(cacheService).deleteByPrefix(any());
    }

    @Test
    @DisplayName("重复正确提交返回已完成幂等结果且不再计分")
    void shouldReturnAlreadySolvedWithoutAwardingAgain() {
        Solve existing = new Solve();
        existing.setSubmissionId("sub_1");
        existing.setSolvedAt(TimeUtil.parse("2026-10-01T06:24:36Z"));
        existing.setBasePoints(400);
        existing.setBonusPoints(100);
        existing.setSolvePlace(1);
        existing.setSource(SolveSource.ANSWER);
        when(solveMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(solveMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(challengeMapper.selectCount(any(Wrapper.class))).thenReturn(12L);

        SubmissionResultVO result = scoringService.award(principal, challenge,
                SolveSource.ANSWER, "flag{ok}", "req-2", true);

        assertThat(result.completion()).isEqualTo("already_solved");
        assertThat(result.submissionId()).isEqualTo("sub_1");
        assertThat(result.pointsAwarded()).isEqualTo(500);
        verify(solveMapper, never()).insert(any(Solve.class));
    }

    @Test
    @DisplayName("错误答案不产生积分也不写入解题记录")
    void shouldNotScoreWhenIncorrect() {
        when(solveMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(solveMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(challengeMapper.selectCount(any(Wrapper.class))).thenReturn(12L);

        SubmissionResultVO result = scoringService.award(principal, challenge,
                SolveSource.ANSWER, "flag{bad}", "req-3", false);

        assertThat(result.result()).isEqualTo("incorrect");
        assertThat(result.completion()).isNull();
        assertThat(result.pointsAwarded()).isZero();
        assertThat(result.solvePlace()).isNull();
        verify(solveMapper, never()).insert(any(Solve.class));
    }
}

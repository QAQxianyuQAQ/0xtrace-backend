package com.qaqxianyuqaq.backend.service.submission.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.entity.*;
import com.qaqxianyuqaq.backend.domain.enums.*;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.submission.*;
import com.qaqxianyuqaq.backend.security.*;
import com.qaqxianyuqaq.backend.service.challenge.*;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.service.submission.ScoringService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionQueryTest {
    @Mock ChallengeService challengeService;
    @Mock EventService eventService;
    @Mock ScoringService scoringService;
    @Mock TeamService teamService;
    @Mock SubmissionMapper submissionMapper;
    @Mock SolveMapper solveMapper;
    @Mock ChallengeMapper challengeMapper;
    @Mock CredentialCipher credentialCipher;
    @Mock PermissionAssert permissionAssert;
    @Mock ChallengeContainerService challengeContainerService;
    @InjectMocks SubmissionServiceImpl service;
    final UserPrincipal user = new UserPrincipal("usr1", "member", UserRole.CONTESTANT, null, 0);
    final Team team = new Team();

    @BeforeEach void event() {
        EventConfig event = new EventConfig(); event.setId("evt1");
        when(eventService.requireCurrent()).thenReturn(event);
        team.setId("team1"); team.setName("Test team"); team.setEventId("evt1");
    }

    @Test void emptySubmissionPageNeverSendsEmptyInQuery() {
        when(challengeMapper.selectList(any())).thenReturn(List.of(challenge("c1", "evt1", ChallengeStatus.PUBLISHED)));
        when(submissionMapper.selectPage(any(), any())).thenReturn(new Page<Submission>(2, 20, 21));
        var page = service.mySubmissions(user, null, null, null, new PageQueryDTO(2, 20));
        assertThat(page.items()).isEmpty();
        assertThat(page.pagination().total()).isEqualTo(21);
        verify(challengeMapper, never()).selectBatchIds(anyCollection());
    }

    @Test void teamWithNoSolvesNeverSendsEmptyInQuery() {
        when(teamService.findMembership("evt1", "usr1")).thenReturn(team);
        when(solveMapper.selectList(any())).thenReturn(List.of());
        when(challengeMapper.selectCount(any())).thenReturn(3L);
        var progress = service.myProgress(user, null);
        assertThat(progress.solved()).isZero();
        assertThat(progress.total()).isEqualTo(3);
        assertThat(progress.score()).isZero();
        assertThat(progress.solvedChallengeCodes()).isEmpty();
        verify(challengeMapper, never()).selectBatchIds(anyCollection());
    }

    @Test void unteamedPlayerGetsZeroProgressWithoutSolveLookup() {
        when(challengeMapper.selectCount(any())).thenReturn(2L);
        var progress = service.myProgress(user, null);
        assertThat(progress.solved()).isZero();
        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.teamId()).isNull();
        verifyNoInteractions(solveMapper);
    }

    @Test void progressExcludesHiddenAndOtherEventChallengesButKeepsEarnedScore() {
        when(teamService.findMembership("evt1", "usr1")).thenReturn(team);
        when(solveMapper.selectList(any())).thenReturn(List.of(solve("c1"), solve("c2"), solve("c3"), solve("c4")));
        when(challengeMapper.selectCount(any())).thenReturn(2L);
        when(challengeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                challenge("c1", "evt1", ChallengeStatus.PUBLISHED), challenge("c2", "evt1", ChallengeStatus.PAUSED),
                challenge("c3", "evt1", ChallengeStatus.ARCHIVED), challenge("c4", "evt2", ChallengeStatus.PUBLISHED)));
        var progress = service.myProgress(user, null);
        assertThat(progress.solved()).isEqualTo(1);
        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.solvedChallengeCodes()).containsExactly("c1");
        assertThat(progress.score()).isEqualTo(1640);
    }

    private Challenge challenge(String id, String eventId, ChallengeStatus status) {
        Challenge value = new Challenge(); value.setId(id); value.setCode(id);
        value.setEventId(eventId); value.setStatus(status); return value;
    }
    private Solve solve(String id) {
        Solve value = new Solve(); value.setChallengeId(id); value.setBasePoints(400); value.setBonusPoints(10); return value;
    }
}

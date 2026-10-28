package com.qaqxianyuqaq.backend.service.submission.impl;

import com.qaqxianyuqaq.backend.domain.dto.submission.SubmitAnswerDTO;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeRuntimeType;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.domain.vo.submission.ProgressVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;
import com.qaqxianyuqaq.backend.exception.FlagOwnerException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.submission.SubmissionMapper;
import com.qaqxianyuqaq.backend.security.CredentialCipher;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeContainerService;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.service.submission.ScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 容器题目提交测试：确保只接受本队容器 Flag。 */
@ExtendWith(MockitoExtension.class)
class SubmissionServiceImplTest {

    @Mock
    private ChallengeService challengeService;

    @Mock
    private EventService eventService;

    @Mock
    private ScoringService scoringService;

    @Mock
    private TeamService teamService;

    @Mock
    private SubmissionMapper submissionMapper;

    @Mock
    private SolveMapper solveMapper;

    @Mock
    private ChallengeMapper challengeMapper;

    @Mock
    private CredentialCipher credentialCipher;

    @Mock
    private PermissionAssert permissionAssert;

    @Mock
    private ChallengeContainerService challengeContainerService;

    private SubmissionServiceImpl submissionService;

    private Challenge challenge;

    private Team team;

    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        submissionService = new SubmissionServiceImpl(challengeService, eventService, scoringService,
                teamService, submissionMapper, solveMapper, challengeMapper, credentialCipher,
                permissionAssert, challengeContainerService);
        principal = new UserPrincipal("usr_1", "member", UserRole.CONTESTANT, "team_1", 0);
        challenge = new Challenge();
        challenge.setId("chal_1");
        challenge.setEventId("evt_1");
        challenge.setCode("PWN-01");
        challenge.setTitle("容器题");
        challenge.setStatus(ChallengeStatus.PUBLISHED);
        challenge.setRuntimeType(ChallengeRuntimeType.DOCKER);
        challenge.setPoints(500);
        team = new Team();
        team.setId("team_1");
        team.setEventId("evt_1");
        team.setName("North Signal");
        EventConfig event = new EventConfig();
        event.setId("evt_1");
        event.setStatus(EventStatus.OPEN);
        when(challengeService.requireByCode("PWN-01")).thenReturn(challenge);
        when(eventService.requireById("evt_1")).thenReturn(event);
        when(teamService.requireMembership("evt_1", "usr_1")).thenReturn(team);
    }

    @Test
    @DisplayName("提交其他队伍 Flag 时返回 FlagOwnerException 且不计分")
    void shouldRejectForeignFlag() {
        ChallengeContainerInstance instance = new ChallengeContainerInstance();
        instance.setId("cinst_1");
        instance.setFlagSecret("encrypted-own-flag");
        when(challengeContainerService.requireReady(challenge, team)).thenReturn(instance);
        when(credentialCipher.decrypt("encrypted-own-flag")).thenReturn("sacc{own-team}");

        assertThatThrownBy(() -> submissionService.submit("PWN-01",
                new SubmitAnswerDTO("sacc{other-team}", null), principal))
                .isInstanceOf(FlagOwnerException.class)
                .hasMessage("串通flag并不是好习惯")
                .extracting("teamName", "username")
                .containsExactly("North Signal", "member");
        verify(scoringService, never()).award(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("提交本队 Flag 时进入正常计分流程")
    void shouldAcceptOwnFlag() {
        ChallengeContainerInstance instance = new ChallengeContainerInstance();
        instance.setId("cinst_1");
        instance.setFlagSecret("encrypted-own-flag");
        when(challengeContainerService.requireReady(challenge, team)).thenReturn(instance);
        when(credentialCipher.decrypt("encrypted-own-flag")).thenReturn("sacc{own-team}");
        SubmissionResultVO expected = new SubmissionResultVO("sub_1", "PWN-01", "correct",
                "first_solve", LocalDateTime.now(), 500, 0, 500, 1,
                new ProgressVO(1, 1), "answer");
        when(scoringService.award(eq(principal), eq(challenge), any(), eq("sacc{own-team}"),
                isNull(), eq(true))).thenReturn(expected);

        SubmissionResultVO actual = submissionService.submit("PWN-01",
                new SubmitAnswerDTO("sacc{own-team}", null), principal);

        org.assertj.core.api.Assertions.assertThat(actual).isSameAs(expected);
    }
}

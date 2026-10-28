package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeRuntimeType;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.ContainerStatus;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ChallengeRuntimeVO;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeContainerInstanceMapper;
import com.qaqxianyuqaq.backend.security.CredentialCipher;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 队伍容器生命周期测试：覆盖单队伍实例和延长窗口。 */
@ExtendWith(MockitoExtension.class)
class ChallengeContainerServiceImplTest {

    @Mock
    private ChallengeContainerInstanceMapper instanceMapper;

    @Mock
    private CredentialCipher credentialCipher;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EventService eventService;

    private ChallengeContainerServiceImpl service;

    private Challenge challenge;

    private Team team;

    @BeforeEach
    void setUp() {
        TraceQuestProperties properties = new TraceQuestProperties();
        properties.getRuntime().setExtensionWindow(Duration.ofMinutes(30));
        properties.getRuntime().setExtensionDuration(Duration.ofHours(1));
        service = new ChallengeContainerServiceImpl(instanceMapper, credentialCipher, passwordEncoder,
                properties, eventService);
        challenge = new Challenge();
        challenge.setId("chal_1");
        challenge.setEventId("evt_1");
        challenge.setCode("PWN-01");
        challenge.setStatus(ChallengeStatus.PUBLISHED);
        challenge.setRuntimeType(ChallengeRuntimeType.DOCKER);
        challenge.setDockerImage("example/challenge");
        challenge.setDockerTag("1.0");
        challenge.setFlagEnvName("FLAG");
        challenge.setFlagPrefix("sacc");
        team = new Team();
        team.setId("team_1");
        team.setEventId("evt_1");
        team.setName("North Signal");
        EventConfig event = new EventConfig();
        event.setId("evt_1");
        event.setStatus(EventStatus.OPEN);
        when(eventService.requireById("evt_1")).thenReturn(event);
    }

    @Test
    @DisplayName("剩余十分钟时可延长一小时")
    void shouldExtendWithinLastThirtyMinutes() {
        ChallengeContainerInstance instance = readyInstance(challenge.getId(), Duration.ofMinutes(10));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(instance);

        ChallengeRuntimeVO result = service.extend(challenge, team);

        assertThat(result.expiresAt()).isAfter(TimeUtil.now().plusMinutes(65));
        assertThat(result.remainingSeconds()).isGreaterThan(4000);
    }

    @Test
    @DisplayName("剩余时间超过三十分钟时拒绝延长")
    void shouldRejectEarlyExtension() {
        ChallengeContainerInstance instance = readyInstance(challenge.getId(), Duration.ofMinutes(40));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(instance);

        assertThatThrownBy(() -> service.extend(challenge, team))
                .isInstanceOf(ConflictException.class)
                .hasMessage("剩余时间超过30分钟，暂不能延长容器");
    }

    @Test
    @DisplayName("队伍已有其他题目容器时不能复用为当前题目")
    void shouldRejectSecondContainer() {
        ChallengeContainerInstance other = readyInstance("chal_other", Duration.ofMinutes(40));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(other);

        assertThatThrownBy(() -> service.extend(challenge, team))
                .isInstanceOf(ConflictException.class)
                .hasMessage("请先启动本队题目容器");
    }

    /** 构造未到期且已就绪的队伍实例。 */
    private ChallengeContainerInstance readyInstance(String challengeId, Duration remaining) {
        ChallengeContainerInstance instance = new ChallengeContainerInstance();
        instance.setId("cinst_1");
        instance.setChallengeId(challengeId);
        instance.setTeamId(team.getId());
        instance.setStatus(ContainerStatus.READY);
        instance.setContainerPort(8080);
        instance.setExpiresAt(TimeUtil.now().plus(remaining));
        return instance;
    }
}

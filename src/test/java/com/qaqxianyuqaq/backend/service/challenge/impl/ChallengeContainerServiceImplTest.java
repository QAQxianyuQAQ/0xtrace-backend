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
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @TempDir
    private Path tempDir;

    private TraceQuestProperties properties;

    private ChallengeContainerServiceImpl service;

    private Challenge challenge;

    private Team team;

    private EventConfig event;

    @BeforeEach
    void setUp() {
        properties = new TraceQuestProperties();
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
        event = new EventConfig();
        event.setId("evt_1");
        event.setStatus(EventStatus.OPEN);
    }

    @Test
    @DisplayName("剩余十分钟时可延长一小时")
    void shouldExtendWithinLastThirtyMinutes() {
        stubOpenEvent();
        ChallengeContainerInstance instance = readyInstance(challenge.getId(), Duration.ofMinutes(10));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(instance);

        ChallengeRuntimeVO result = service.extend(challenge, team);

        assertThat(result.expiresAt()).isAfter(TimeUtil.now().plusMinutes(65));
        assertThat(result.remainingSeconds()).isGreaterThan(4000);
    }

    @Test
    @DisplayName("剩余时间超过三十分钟时拒绝延长")
    void shouldRejectEarlyExtension() {
        stubOpenEvent();
        ChallengeContainerInstance instance = readyInstance(challenge.getId(), Duration.ofMinutes(40));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(instance);

        assertThatThrownBy(() -> service.extend(challenge, team))
                .isInstanceOf(ConflictException.class)
                .hasMessage("剩余时间超过30分钟，暂不能延长容器");
    }

    @Test
    @DisplayName("队伍已有其他题目容器时不能复用为当前题目")
    void shouldRejectSecondContainer() {
        stubOpenEvent();
        ChallengeContainerInstance other = readyInstance("chal_other", Duration.ofMinutes(40));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(other);

        assertThatThrownBy(() -> service.extend(challenge, team))
                .isInstanceOf(ConflictException.class)
                .hasMessage("请先启动本队题目容器");
    }

    @Test
    @DisplayName("同队伍重复启动时复用正在启动的实例")
    void shouldReusePendingInstance() {
        stubOpenEvent();
        ChallengeContainerInstance pending = readyInstance(challenge.getId(), Duration.ofMinutes(10));
        pending.setStatus(ContainerStatus.PENDING);
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(pending);

        ChallengeRuntimeVO result = service.ensureStarted(challenge, team);

        assertThat(result.instanceId()).isEqualTo(pending.getId());
        assertThat(result.status()).isEqualTo(ContainerStatus.PENDING.name());
        verify(instanceMapper, never()).insert(any(ChallengeContainerInstance.class));
        verify(instanceMapper, never()).deleteById(any(ChallengeContainerInstance.class));
    }

    @Test
    @DisplayName("待启动实例的期限覆盖最慢启动流程")
    void shouldKeepPendingInstanceUntilDockerStartupCanFinish() throws IOException {
        stubOpenEvent();
        useDockerScript("exit 1");

        assertThatThrownBy(() -> service.ensureStarted(challenge, team))
                .isInstanceOf(com.qaqxianyuqaq.backend.exception.ContainerRuntimeException.class);

        ArgumentCaptor<ChallengeContainerInstance> inserted = ArgumentCaptor.forClass(ChallengeContainerInstance.class);
        verify(instanceMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getExpiresAt()).isAfter(TimeUtil.now().plusMinutes(36));
    }

    @Test
    @DisplayName("正常启动窗口内查询状态不调用阻塞式 Docker 检查")
    void shouldReturnStartingWithoutInspectingDockerBeforeStartupBudgetExpires() {
        ChallengeContainerInstance starting = readyInstance(challenge.getId(), Duration.ofMinutes(30));
        starting.setStatus(ContainerStatus.STARTING);
        starting.setUpdatedAt(TimeUtil.now());
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(starting);

        properties.getRuntime().setDockerBinary(tempDir.resolve("docker-must-not-run").toString());

        ChallengeRuntimeVO result = service.status(challenge, team);

        assertThat(result.status()).isEqualTo(ContainerStatus.STARTING.name());
    }

    @Test
    @DisplayName("超时的启动实例恢复运行容器并重新计算完整 TTL")
    void shouldRecoverStaleStartingInstance() throws IOException {
        ChallengeContainerInstance starting = readyInstance(challenge.getId(), Duration.ofMinutes(1));
        starting.setStatus(ContainerStatus.STARTING);
        starting.setUpdatedAt(TimeUtil.now().minusMinutes(18));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(starting);

        try (ServerSocket readyPort = new ServerSocket(0)) {
            int port = readyPort.getLocalPort();
            starting.setContainerPort(port);
            useDockerScript("printf '%s\\n' '[{\"Id\":\"container-id\",\"State\":{\"Status\":\"running\"},"
                    + "\"NetworkSettings\":{\"Networks\":{\"tracequest-runtime\":{\"IPAddress\":\"127.0.0.1\"}},\"Ports\":{}}}]'");

            ChallengeRuntimeVO result = service.status(challenge, team);

            assertThat(result.status()).isEqualTo(ContainerStatus.READY.name());
            assertThat(starting.getExpiresAt()).isAfter(TimeUtil.now().plusMinutes(59));
            verify(instanceMapper).updateById(starting);
        }
    }

    @Test
    @DisplayName("超时且未运行的启动实例返回失败而不是永久启动中")
    void shouldFailStaleStartingInstanceWithoutRunningContainer() throws IOException {
        ChallengeContainerInstance starting = readyInstance(challenge.getId(), Duration.ofMinutes(1));
        starting.setStatus(ContainerStatus.STARTING);
        starting.setUpdatedAt(TimeUtil.now().minusMinutes(18));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(starting);

        useDockerScript("printf '%s\\n' '[{\"Id\":\"container-id\",\"State\":{\"Status\":\"exited\"},"
                + "\"NetworkSettings\":{\"Networks\":{\"tracequest-runtime\":{\"IPAddress\":\"127.0.0.1\"}},\"Ports\":{}}}]'");

        ChallengeRuntimeVO result = service.status(challenge, team);

        assertThat(result.status()).isEqualTo(ContainerStatus.FAILED.name());
        assertThat(result.lastError()).contains("启动超时");
        assertThat(starting.getLastError()).contains("启动超时");
        verify(instanceMapper).updateById(starting);
    }


    @Test
    @DisplayName("镜像拉取失败仍保存 FAILED 并返回受控异常而非未处理 500")
    void shouldPersistSafeFailureWhenImageCannotBePrepared() throws IOException {
        stubOpenEvent();
        useDockerScript("if [ \"$1\" = network ]; then exit 0; fi\necho 'private failure detail'; exit 1");
        assertThatThrownBy(() -> service.ensureStarted(challenge, team))
                .isInstanceOf(com.qaqxianyuqaq.backend.exception.ContainerRuntimeException.class)
                .hasMessageContaining("预加载镜像");
        ArgumentCaptor<ChallengeContainerInstance> inserted = ArgumentCaptor.forClass(ChallengeContainerInstance.class);
        verify(instanceMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(ContainerStatus.FAILED);
        assertThat(inserted.getValue().getLastError()).contains("预加载镜像").doesNotContain("private failure detail");
    }

    @Test
    @DisplayName("已缓存镜像启动完成后保存地址并从就绪开始计算一小时")
    void shouldStartCachedImageAndExposeReadyState() throws IOException {
        stubOpenEvent();
        try (ServerSocket listener = new ServerSocket(0)) {
            int port = listener.getLocalPort();
            useDockerScript("case \"$1\" in\n"
                    + "network|rm) exit 0;;\n"
                    + "image) echo '[{\"Config\":{\"ExposedPorts\":{\"" + port + "/tcp\":{}}}}]';;\n"
                    + "run) echo '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';;\n"
                    + "inspect) echo '[{\"Id\":\"container-id\",\"State\":{\"Status\":\"running\"},"
                    + "\"NetworkSettings\":{\"Networks\":{\"tracequest-runtime\":{\"IPAddress\":\"127.0.0.1\"}},"
                    + "\"Ports\":{\"" + port + "/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"12345\"}]}}}]';;\n"
                    + "*) exit 91;;\nesac");
            ChallengeRuntimeVO result = service.ensureStarted(challenge, team);
            assertThat(result.status()).isEqualTo("READY");
            assertThat(result.hostPort()).isEqualTo(12345);
            assertThat(result.containerPort()).isEqualTo(port);
            assertThat(result.remainingSeconds()).isBetween(3590L, 3600L);
        }
    }

    @Test
    @DisplayName("查询过期实例只报告停止，不调用 Docker 或删除记录")
    void shouldNotDestroyExpiredInstanceOnStatusRead() {
        ChallengeContainerInstance expired = readyInstance(challenge.getId(), Duration.ofMinutes(-1));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(expired);
        properties.getRuntime().setDockerBinary(tempDir.resolve("docker-must-not-run").toString());
        assertThat(service.status(challenge, team).status()).isEqualTo("STOPPED");
        verify(instanceMapper, never()).deleteById(any(ChallengeContainerInstance.class));
        verify(instanceMapper, never()).updateById(any(ChallengeContainerInstance.class));
    }

    @Test
    @DisplayName("Docker 检查暂时失败不能删除仍存在的实例或重置 Flag")
    void shouldPreserveReadyInstanceWhenDockerInspectionFails() throws IOException {
        stubOpenEvent();
        ChallengeContainerInstance ready = readyInstance(challenge.getId(), Duration.ofMinutes(20));
        ready.setFlagSecret("original-encrypted-flag");
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(ready);
        useDockerScript("case \"$1\" in\n"
                + "inspect) echo 'temporary daemon failure'; exit 1;;\n"
                + "container) echo '" + ready.getContainerName() + "'; exit 0;;\n"
                + "*) exit 91;;\nesac");
        assertThatThrownBy(() -> service.ensureStarted(challenge, team))
                .isInstanceOf(com.qaqxianyuqaq.backend.exception.ContainerRuntimeException.class)
                .hasMessageContaining("原实例已保留");
        assertThat(ready.getFlagSecret()).isEqualTo("original-encrypted-flag");
        verify(instanceMapper, never()).deleteById(ready.getId());
        verify(instanceMapper, never()).insert(any(ChallengeContainerInstance.class));
        verify(instanceMapper, never()).updateById(any(ChallengeContainerInstance.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("销毁未成功时保留数据库实例和队伍配额")
    void shouldKeepInstanceRecordWhenDockerRemovalFails() throws IOException {
        ChallengeContainerInstance ready = readyInstance(challenge.getId(), Duration.ofMinutes(20));
        when(instanceMapper.selectOne(any(Wrapper.class))).thenReturn(ready);
        useDockerScript("if [ \"$1\" = container ]; then echo '" + ready.getContainerName() + "'; exit 0; fi\nexit 1");
        assertThatThrownBy(() -> service.stop(challenge, team))
                .isInstanceOf(com.qaqxianyuqaq.backend.exception.ContainerRuntimeException.class);
        verify(instanceMapper, never()).deleteById(ready.getId());
    }

    @Test
    @DisplayName("批量销毁遇到一个失败时保留其状态并继续处理其他队伍")
    void shouldPreserveFailedBulkRemovalAndContinueOtherTeams() throws IOException {
        ChallengeContainerInstance first = readyInstance(challenge.getId(), Duration.ofMinutes(20));
        ChallengeContainerInstance second = readyInstance(challenge.getId(), Duration.ofMinutes(20));
        second.setId("cinst_2"); second.setContainerName("tq-other-team");
        when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(first, second));
        useDockerScript("if [ \"$1\" = rm ] && [ \"$3\" = '" + first.getContainerName() + "' ]; then exit 1; fi\n"
                + "if [ \"$1\" = container ]; then echo '" + first.getContainerName() + "'; fi\nexit 0");
        assertThatThrownBy(() -> service.stopByChallenge(challenge.getId()))
                .isInstanceOf(com.qaqxianyuqaq.backend.exception.ContainerRuntimeException.class);
        assertThat(first.getStatus()).isEqualTo(ContainerStatus.READY);
        assertThat(second.getStatus()).isEqualTo(ContainerStatus.STOPPED);
        verify(instanceMapper, never()).updateById(first);
        verify(instanceMapper).updateById(second);
    }

    @Test
    @DisplayName("定时清理保留删除失败记录并继续清理其他已到期实例")
    void shouldKeepFailedExpiryCleanupAndContinueOtherInstances() throws IOException {
        ChallengeContainerInstance first = readyInstance(challenge.getId(), Duration.ofMinutes(-2));
        ChallengeContainerInstance second = readyInstance(challenge.getId(), Duration.ofMinutes(-2));
        second.setId("cinst_2"); second.setContainerName("tq-other-team");
        when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(first, second));
        useDockerScript("if [ \"$1\" = rm ] && [ \"$3\" = '" + first.getContainerName() + "' ]; then exit 1; fi\n"
                + "if [ \"$1\" = container ]; then echo '" + first.getContainerName() + "'; fi\nexit 0");
        assertThatThrownBy(() -> service.cleanupExpired())
                .isInstanceOf(com.qaqxianyuqaq.backend.exception.ContainerRuntimeException.class);
        verify(instanceMapper, never()).deleteById(first.getId());
        verify(instanceMapper).deleteById(second.getId());
    }

    @Test
    @DisplayName("启动失败后的容器清理也失败时保存明确的失败状态供重试")
    void shouldMarkFailedWhenStartupRollbackCannotRemoveContainer() throws IOException {
        stubOpenEvent();
        properties.getRuntime().setStartTimeout(Duration.ofMillis(10));
        properties.getRuntime().setHealthInterval(Duration.ofMillis(1));
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path removed = tempDir.resolve("first-remove-completed");
        useDockerScript("case \"$1\" in\nnetwork) exit 0;;\n"
                + "rm) if [ -e '" + removed + "' ]; then exit 1; fi; touch '" + removed + "'; exit 0;;\n"
                + "image) echo '[{\"Config\":{\"ExposedPorts\":{\"" + port + "/tcp\":{}}}}]';;\n"
                + "run) echo '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';;\n"
                + "inspect) echo '[{\"Id\":\"container-id\",\"State\":{\"Status\":\"running\"},"
                + "\"NetworkSettings\":{\"Networks\":{\"tracequest-runtime\":{\"IPAddress\":\"127.0.0.1\"}},\"Ports\":{}}}]';;\n"
                + "container) echo 'tq-chal_1-team_1';;\n*) exit 91;;\nesac");
        assertThatThrownBy(() -> service.ensureStarted(challenge, team))
                .isInstanceOf(com.qaqxianyuqaq.backend.exception.ContainerRuntimeException.class);
        ArgumentCaptor<ChallengeContainerInstance> inserted = ArgumentCaptor.forClass(ChallengeContainerInstance.class);
        verify(instanceMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(ContainerStatus.FAILED);
        assertThat(inserted.getValue().getLastError()).contains("原实例已保留");
    }

    private void useDockerScript(String body) throws IOException {
        Path executable = tempDir.resolve("fake-docker");
        Files.writeString(executable, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        properties.getRuntime().setDockerBinary(executable.toString());
    }

    private void stubOpenEvent() {
        when(eventService.requireById("evt_1")).thenReturn(event);
    }

    /** 构造未到期且已就绪的队伍实例。 */
    private ChallengeContainerInstance readyInstance(String challengeId, Duration remaining) {
        ChallengeContainerInstance instance = new ChallengeContainerInstance();
        instance.setId("cinst_1");
        instance.setChallengeId(challengeId);
        instance.setTeamId(team.getId());
        instance.setContainerName("tq-" + challengeId + "-" + team.getId());
        instance.setStatus(ContainerStatus.READY);
        instance.setContainerPort(8080);
        instance.setExpiresAt(TimeUtil.now().plus(remaining));
        return instance;
    }
}

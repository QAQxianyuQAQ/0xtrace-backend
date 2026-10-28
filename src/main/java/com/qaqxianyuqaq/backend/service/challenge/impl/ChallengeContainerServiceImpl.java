package com.qaqxianyuqaq.backend.service.challenge.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.aspect.DistributedLock;
import com.qaqxianyuqaq.backend.config.RuntimeProperties;
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
import com.qaqxianyuqaq.backend.exception.ContainerRuntimeException;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeContainerInstanceMapper;
import com.qaqxianyuqaq.backend.security.CredentialCipher;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeContainerService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.util.DockerCliUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** 单机 Docker 队伍容器服务实现。 */
@Service
public class ChallengeContainerServiceImpl implements ChallengeContainerService {

    private final ChallengeContainerInstanceMapper instanceMapper;

    private final CredentialCipher credentialCipher;

    private final PasswordEncoder passwordEncoder;

    private final TraceQuestProperties properties;

    private final EventService eventService;

    public ChallengeContainerServiceImpl(ChallengeContainerInstanceMapper instanceMapper,
                                         CredentialCipher credentialCipher, PasswordEncoder passwordEncoder,
                                         TraceQuestProperties properties, EventService eventService) {
        this.instanceMapper = instanceMapper;
        this.credentialCipher = credentialCipher;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.eventService = eventService;
    }

    @Override
    @DistributedLock(key = "'team-instance:' + #team.id")
    public ChallengeRuntimeVO ensureStarted(Challenge challenge, Team team) {
        // 1. 校验题目启用了 Docker 模式并检查单机运行时配置
        RuntimeProperties runtime = properties.getRuntime();
        assertDockerChallenge(challenge, runtime);
        assertPlayable(challenge);

        // 2. 查询队伍唯一实例；同题运行中实例直接复用，其他题目仍在运行则拒绝新开
        ChallengeContainerInstance instance = findByTeam(team.getId());
        if (instance != null) {
            if (isExpired(instance) || instance.getStatus() != ContainerStatus.READY
                    || !containerStillRunning(instance, runtime)) {
                destroyAndDelete(instance, runtime);
                instance = null;
            } else if (!challenge.getId().equals(instance.getChallengeId())) {
                throw new ConflictException("每个队伍同时只能运行一个题目容器，请先摧毁当前容器");
            } else {
                return toVO(challenge, instance);
            }
        }

        // 3. 准备内部网络并拉取题目镜像
        DockerCliUtil.ensureInternalNetwork(runtime.getDockerBinary(), runtime.getNetwork(),
                runtime.getCommandTimeout());
        DockerCliUtil.pull(runtime.getDockerBinary(), challenge.getDockerImage(), challenge.getDockerTag(),
                runtime.getCommandTimeout());

        // 4. 从镜像 EXPOSE 自动解析内部端口，管理端不再填写 containerPort
        int containerPort;
        try {
            containerPort = DockerCliUtil.inspectImagePort(runtime.getDockerBinary(),
                    challenge.getDockerImage(), challenge.getDockerTag(), runtime.getCommandTimeout());
        } catch (RuntimeException exception) {
            throw new ContainerRuntimeException("无法确定题目镜像监听端口");
        }

        // 5. 为本队生成新的带盐 Flag，并创建一小时有效的实例记录
        String flag = resolveFlag(challenge, team);
        instance = newInstance(challenge, team, flag, containerPort);
        instanceMapper.insert(instance);
        markStatus(instance, ContainerStatus.PULLING, null);

        // 6. 清理同名遗留容器并注入本队 Flag 启动实例
        DockerCliUtil.removeQuietly(runtime.getDockerBinary(), instance.getContainerName(),
                runtime.getCommandTimeout());
        markStatus(instance, ContainerStatus.STARTING, null);
        String containerId;
        try {
            containerId = DockerCliUtil.startContainer(runtime.getDockerBinary(), instance.getContainerName(),
                    challenge.getId(), team.getId(), runtime.getNetwork(), challenge.getDockerImage(),
                    challenge.getDockerTag(), challenge.getFlagEnvName(), flag, containerPort,
                    runtime.getMemoryLimit(), runtime.getCpuLimit(), runtime.getPidsLimit(),
                    runtime.getCommandTimeout());
        } catch (RuntimeException exception) {
            markFailed(instance, exception);
            throw new ContainerRuntimeException("题目容器启动失败");
        }

        // 7. 读取 Docker 随机宿主端口和内部 IP，并等待容器业务端口就绪
        instance.setContainerId(containerId);
        instance.setExpiresAt(TimeUtil.now().plus(runtime.getInstanceTtl()));
        DockerCliUtil.ContainerInfo info = inspectOrFail(instance, runtime);
        if (!DockerCliUtil.waitForPort(info.containerIp(), containerPort,
                runtime.getStartTimeout(), runtime.getHealthInterval())) {
            DockerCliUtil.removeQuietly(runtime.getDockerBinary(), instance.getContainerName(),
                    runtime.getCommandTimeout());
            markFailed(instance, new IllegalStateException("容器端口未在限定时间内就绪"));
            throw new ContainerRuntimeException("题目容器启动超时");
        }

        // 8. 记录运行地址和到期时间并将实例标记为就绪
        applyContainerInfo(instance, info);
        instance.setStatus(ContainerStatus.READY);
        instance.setReadyAt(TimeUtil.now());
        instance.setUpdatedAt(TimeUtil.now());
        instanceMapper.updateById(instance);
        return toVO(challenge, instance);
    }

    @Override
    public ChallengeRuntimeVO status(Challenge challenge, Team team) {
        // 1. 查询队伍唯一实例，其他题目的容器或过期实例均视为当前题目未运行
        ChallengeContainerInstance instance = findByTeam(team.getId());
        if (instance == null || !challenge.getId().equals(instance.getChallengeId())) {
            return new ChallengeRuntimeVO(null, challenge.getCode(), team.getId(), ContainerStatus.STOPPED.name(),
                    null, null, null, null, null, null, null, null, 0L);
        }

        // 2. 发现过期实例时立即销毁并返回停止状态
        if (isExpired(instance)) {
            destroyAndDelete(instance, properties.getRuntime());
            return new ChallengeRuntimeVO(null, challenge.getCode(), team.getId(), ContainerStatus.STOPPED.name(),
                    null, null, null, null, null, "容器已到期", null, null, 0L);
        }

        // 3. 将数据库状态转换为前端可展示的运行状态
        return toVO(challenge, instance);
    }

    @Override
    public void stop(Challenge challenge, Team team) {
        // 1. 查询队伍唯一实例，当前题目未运行时不处理
        ChallengeContainerInstance instance = findByTeam(team.getId());
        if (instance == null || !challenge.getId().equals(instance.getChallengeId())) {
            return;
        }

        // 2. 删除 Docker 容器并更新数据库终态
        RuntimeProperties runtime = properties.getRuntime();
        DockerCliUtil.removeQuietly(runtime.getDockerBinary(), instance.getContainerName(),
                runtime.getCommandTimeout());
        instanceMapper.deleteById(instance.getId());
    }

    @Override
    public ChallengeContainerInstance requireReady(Challenge challenge, Team team) {
        // 1. 校验题目已发布且赛事处于开放时间窗
        assertPlayable(challenge);

        // 2. 读取队伍唯一实例并校验其属于当前题目且未过期
        ChallengeContainerInstance instance = findByTeam(team.getId());
        if (instance == null || !challenge.getId().equals(instance.getChallengeId())
                || instance.getStatus() != ContainerStatus.READY) {
            throw new ConflictException(ErrorCode.CONTAINER_NOT_READY, "请先启动本队题目容器");
        }
        if (isExpired(instance)) {
            destroyAndDelete(instance, properties.getRuntime());
            throw new ConflictException(ErrorCode.CONTAINER_NOT_READY, "本队题目容器已到期");
        }
        return instance;
    }

    @Override
    @DistributedLock(key = "'team-instance-extend:' + #team.id")
    public ChallengeRuntimeVO extend(Challenge challenge, Team team) {
        // 1. 校验当前队伍确实拥有该题目的就绪容器
        ChallengeContainerInstance instance = requireReady(challenge, team);

        // 2. 仅在剩余时间不超过 30 分钟时允许延长，避免无限提前续期
        RuntimeProperties runtime = properties.getRuntime();
        Duration remaining = Duration.between(TimeUtil.now(), instance.getExpiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            destroyAndDelete(instance, runtime);
            throw new ConflictException(ErrorCode.CONTAINER_NOT_READY, "本队题目容器已到期");
        }
        if (remaining.compareTo(runtime.getExtensionWindow()) > 0) {
            throw new ConflictException("剩余时间超过30分钟，暂不能延长容器");
        }

        // 3. 在原到期时间基础上追加一小时并返回最新状态
        instance.setExpiresAt(instance.getExpiresAt().plus(runtime.getExtensionDuration()));
        instance.setUpdatedAt(TimeUtil.now());
        instanceMapper.updateById(instance);
        return toVO(challenge, instance);
    }

    /** 定时销毁所有到期容器，保证实际运行时间不超过配置上限。 */
    @Scheduled(fixedDelayString = "${tracequest.runtime.cleanup-interval:30s}")
    public void cleanupExpired() {
        // 1. 运行时关闭时跳过清理，供自动化测试和非容器环境安全降级
        RuntimeProperties runtime = properties.getRuntime();
        if (!runtime.isEnabled()) {
            return;
        }

        // 2. 查询已经超过 expires_at 的实例
        List<ChallengeContainerInstance> expired = instanceMapper.selectList(
                Wrappers.<ChallengeContainerInstance>lambdaQuery()
                        .and(condition -> condition
                                .isNull(ChallengeContainerInstance::getExpiresAt)
                                .or()
                                .le(ChallengeContainerInstance::getExpiresAt, TimeUtil.now())));

        // 3. 逐项删除容器和实例，下一轮清理会继续处理失败项
        for (ChallengeContainerInstance instance : expired) {
            destroyAndDelete(instance, runtime);
        }
    }

    @Override
    public void stopByChallenge(String challengeId) {
        // 1. 查询题目下全部队伍实例
        List<ChallengeContainerInstance> instances = instanceMapper.selectList(
                Wrappers.<ChallengeContainerInstance>lambdaQuery()
                        .eq(ChallengeContainerInstance::getChallengeId, challengeId));

        // 2. 逐个删除容器并标记停止，单个失败不阻断其余清理
        RuntimeProperties runtime = properties.getRuntime();
        for (ChallengeContainerInstance instance : instances) {
            try {
                DockerCliUtil.removeQuietly(runtime.getDockerBinary(), instance.getContainerName(),
                        runtime.getCommandTimeout());
            } catch (RuntimeException ignored) {
                // 单个容器清理失败不阻断其余队伍实例
            }
            markStatus(instance, ContainerStatus.STOPPED, null);
        }
    }

    /** 校验题目具备完整 Docker 字段。 */
    private void assertDockerChallenge(Challenge challenge, RuntimeProperties runtime) {
        if (!runtime.isEnabled()) {
            throw new ContainerRuntimeException("当前环境未启用题目容器");
        }
        if (challenge.getRuntimeType() != ChallengeRuntimeType.DOCKER
                || blank(challenge.getDockerImage()) || blank(challenge.getDockerTag())
                || blank(challenge.getFlagEnvName()) || blank(challenge.getFlagPrefix())) {
            throw new ValidationFailedException("runtimeType", "题目未完整配置 Docker 运行时");
        }
    }

    /** 校验题目已发布且赛事当前允许作答。 */
    private void assertPlayable(Challenge challenge) {
        if (challenge.getStatus() != ChallengeStatus.PUBLISHED) {
            throw new ConflictException(ErrorCode.CONTAINER_NOT_READY, "题目当前不可启动容器");
        }
        EventConfig event = eventService.requireById(challenge.getEventId());
        if (event.getStatus() != EventStatus.OPEN) {
            throw new ConflictException(ErrorCode.SUBMISSION_CLOSED, "赛事当前不可作答");
        }
        var now = TimeUtil.now();
        if (event.getStartsAt() != null && now.isBefore(event.getStartsAt())) {
            throw new ConflictException(ErrorCode.SUBMISSION_CLOSED, "赛事尚未开始");
        }
        if (event.getEndsAt() != null && now.isAfter(event.getEndsAt())) {
            throw new ConflictException(ErrorCode.SUBMISSION_CLOSED, "赛事已结束");
        }
    }

    /** 为新建容器生成带随机盐的队伍 Flag。 */
    private String resolveFlag(Challenge challenge, Team team) {
        String hash = passwordEncoder.encode(team.getName());
        return challenge.getFlagPrefix() + "{" + hash + "}";
    }

    /** 新建待启动实例。 */
    private ChallengeContainerInstance newInstance(Challenge challenge, Team team, String flag, int containerPort) {
        ChallengeContainerInstance instance = new ChallengeContainerInstance();
        instance.setId(IdGenerator.next("cinst"));
        instance.setEventId(challenge.getEventId());
        instance.setChallengeId(challenge.getId());
        instance.setTeamId(team.getId());
        instance.setContainerName("tq-" + challenge.getId() + "-" + team.getId());
        instance.setStatus(ContainerStatus.PENDING);
        instance.setDockerImage(challenge.getDockerImage());
        instance.setDockerTag(challenge.getDockerTag());
        instance.setFlagEnvName(challenge.getFlagEnvName());
        instance.setFlagSecret(credentialCipher.encrypt(flag));
        instance.setContainerPort(containerPort);
        instance.setExpiresAt(TimeUtil.now().plus(properties.getRuntime().getInstanceTtl()));
        instance.setCreatedAt(TimeUtil.now());
        instance.setUpdatedAt(TimeUtil.now());
        return instance;
    }

    /** 查询队伍唯一实例，数据库唯一约束保证队伍只能存在一个容器。 */
    private ChallengeContainerInstance findByTeam(String teamId) {
        return instanceMapper.selectOne(Wrappers.<ChallengeContainerInstance>lambdaQuery()
                .eq(ChallengeContainerInstance::getTeamId, teamId));
    }

    /** 判断实例是否超过到期时间。 */
    private boolean isExpired(ChallengeContainerInstance instance) {
        return instance.getExpiresAt() == null || !TimeUtil.now().isBefore(instance.getExpiresAt());
    }

    /** 销毁 Docker 容器并删除实例记录，释放队伍唯一容器额度。 */
    private void destroyAndDelete(ChallengeContainerInstance instance, RuntimeProperties runtime) {
        DockerCliUtil.removeQuietly(runtime.getDockerBinary(), instance.getContainerName(),
                runtime.getCommandTimeout());
        instanceMapper.deleteById(instance.getId());
    }

    /** 检查已有 Docker 容器是否仍在运行。 */
    private boolean containerStillRunning(ChallengeContainerInstance instance, RuntimeProperties runtime) {
        if (blank(instance.getContainerId())) {
            return false;
        }
        try {
            DockerCliUtil.ContainerInfo info = DockerCliUtil.inspect(runtime.getDockerBinary(),
                    instance.getContainerId(), runtime.getNetwork(), instance.getContainerPort(),
                    runtime.getCommandTimeout());
            return "running".equalsIgnoreCase(info.state());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 读取容器状态，失败时清理并标记实例失败。 */
    private DockerCliUtil.ContainerInfo inspectOrFail(ChallengeContainerInstance instance, RuntimeProperties runtime) {
        try {
            return DockerCliUtil.inspect(runtime.getDockerBinary(), instance.getContainerId(),
                    runtime.getNetwork(), instance.getContainerPort(), runtime.getCommandTimeout());
        } catch (RuntimeException exception) {
            markFailed(instance, exception);
            throw new ContainerRuntimeException("无法读取题目容器状态");
        }
    }

    /** 将 Docker 检查结果写入实例。 */
    private void applyContainerInfo(ChallengeContainerInstance instance, DockerCliUtil.ContainerInfo info) {
        instance.setContainerIp(info.containerIp());
        instance.setHostIp(info.hostIp());
        instance.setHostPort(info.hostPort());
        instance.setUpdatedAt(TimeUtil.now());
    }

    /** 更新实例状态。 */
    private void markStatus(ChallengeContainerInstance instance, ContainerStatus status, String error) {
        instance.setStatus(status);
        instance.setLastError(error);
        instance.setUpdatedAt(TimeUtil.now());
        instanceMapper.updateById(instance);
    }

    /** 记录失败原因并清理容器；对外不返回 Docker 原始输出。 */
    private void markFailed(ChallengeContainerInstance instance, RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && message.length() > 500) {
            message = message.substring(0, 500);
        }
        markStatus(instance, ContainerStatus.FAILED, message);
    }

    /** 实例转响应对象，不返回 Flag 和环境变量值。 */
    private ChallengeRuntimeVO toVO(Challenge challenge, ChallengeContainerInstance instance) {
        Duration remaining = instance.getExpiresAt() == null ? Duration.ZERO
                : Duration.between(TimeUtil.now(), instance.getExpiresAt());
        return new ChallengeRuntimeVO(instance.getId(), challenge.getCode(), instance.getTeamId(),
                instance.getStatus().name(), instance.getContainerIp(), instance.getHostIp(),
                instance.getHostPort(), instance.getContainerPort(),
                properties.getRuntime().getPublicWssUrl(), instance.getLastError(), instance.getReadyAt(),
                instance.getExpiresAt(), Math.max(0L, remaining.toSeconds()));
    }

    /** 判断字符串为空。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

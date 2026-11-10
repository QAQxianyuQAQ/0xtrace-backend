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
    @DistributedLock(key = "'team-instance:' + #p1.id")
    public ChallengeRuntimeVO ensureStarted(Challenge challenge, Team team) {
        // 1. 校验题目启用了 Docker 模式并检查单机运行时配置
        RuntimeProperties runtime = properties.getRuntime();
        assertDockerChallenge(challenge, runtime);
        assertPlayable(challenge);

        // 2. 查询队伍唯一实例；复用同题启动任务，避免轮询或重试删除进行中的容器
        ChallengeContainerInstance instance = findByTeam(team.getId());
        if (instance != null) {
            boolean sameChallenge = challenge.getId().equals(instance.getChallengeId());
            if (!sameChallenge && !isExpired(instance) && instance.getStatus() != ContainerStatus.FAILED
                    && instance.getStatus() != ContainerStatus.STOPPED) {
                throw new ConflictException("每个队伍同时只能运行一个题目容器，请先摧毁当前容器");
            }

            if (sameChallenge && !isExpired(instance)) {
                if (instance.getStatus() == ContainerStatus.PENDING
                        || instance.getStatus() == ContainerStatus.PULLING
                        || instance.getStatus() == ContainerStatus.STARTING) {
                    return toVO(challenge, instance);
                }
                if (instance.getStatus() == ContainerStatus.READY && containerStillRunning(instance, runtime)) {
                    return toVO(challenge, instance);
                }
            }

            // 过期、失败、停止或 Docker 中已不存在的实例可安全清理后重试
            destroyAndDelete(instance, runtime);
        }

        // 3. 在任何 Docker 操作前落库，使选手可查询到 PENDING 状态
        String flag = resolveFlag(challenge, team);
        instance = newInstance(challenge, team, flag);
        instanceMapper.insert(instance);
        boolean containerStartAttempted = false;
        try {
            // 4. 拉取阶段可能包含多个限时 Docker 命令；先写入状态供前端快速轮询
            markStatus(instance, ContainerStatus.PULLING, null);
            DockerCliUtil.ensureInternalNetwork(runtime.getDockerBinary(), runtime.getNetwork(),
                    runtime.getCommandTimeout());
            DockerCliUtil.pull(runtime.getDockerBinary(), challenge.getDockerImage(), challenge.getDockerTag(),
                    runtime.getCommandTimeout());

            // 5. 从镜像 EXPOSE 自动解析内部端口，并保存以供后续状态恢复
            int containerPort = DockerCliUtil.inspectImagePort(runtime.getDockerBinary(),
                    challenge.getDockerImage(), challenge.getDockerTag(), runtime.getCommandTimeout());
            instance.setContainerPort(containerPort);
            instance.setUpdatedAt(TimeUtil.now());
            instanceMapper.updateById(instance);

            // 6. 注入本队 Flag 启动实例
            markStatus(instance, ContainerStatus.STARTING, null);
            DockerCliUtil.removeQuietly(runtime.getDockerBinary(), instance.getContainerName(),
                    runtime.getCommandTimeout());
            containerStartAttempted = true;
            String containerId = DockerCliUtil.startContainer(runtime.getDockerBinary(), instance.getContainerName(),
                    challenge.getId(), team.getId(), runtime.getNetwork(), challenge.getDockerImage(),
                    challenge.getDockerTag(), challenge.getFlagEnvName(), flag, containerPort,
                    runtime.getMemoryLimit(), runtime.getCpuLimit(), runtime.getPidsLimit(),
                    runtime.getCommandTimeout());

            // 7. 读取容器状态并等待题目服务端口就绪
            instance.setContainerId(containerId);
            DockerCliUtil.ContainerInfo info = DockerCliUtil.inspect(runtime.getDockerBinary(), containerId,
                    runtime.getNetwork(), containerPort, runtime.getCommandTimeout());
            if (!DockerCliUtil.waitForPort(info.containerIp(), containerPort,
                    runtime.getStartTimeout(), runtime.getHealthInterval())) {
                throw new ContainerRuntimeException("题目容器启动超时");
            }

            // 8. 端口就绪后才开始计算一小时运行期限，并将实例标记为 READY
            applyContainerInfo(instance, info);
            instance.setExpiresAt(TimeUtil.now().plus(runtime.getInstanceTtl()));
            instance.setStatus(ContainerStatus.READY);
            instance.setReadyAt(TimeUtil.now());
            instance.setUpdatedAt(TimeUtil.now());
            instanceMapper.updateById(instance);
            return toVO(challenge, instance);
        } catch (RuntimeException exception) {
            // 9. 仅在启动命令可能已创建容器时清理；拉取失败不应再阻塞等待 Docker
            if (containerStartAttempted) {
                DockerCliUtil.removeQuietly(runtime.getDockerBinary(), instance.getContainerName(),
                        runtime.getCommandTimeout());
            }
            markFailed(instance, exception);
            if (exception instanceof ContainerRuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ContainerRuntimeException("题目容器启动失败");
        }
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

        // 3. 只在启动阶段超过其 Docker 命令预算后尝试恢复，避免轮询阻塞在 Docker CLI
        reconcileStartup(instance, properties.getRuntime());

        // 4. 将数据库状态转换为前端可展示的运行状态
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
    @DistributedLock(key = "'team-instance-extend:' + #p1.id")
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
    private ChallengeContainerInstance newInstance(Challenge challenge, Team team, String flag) {
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
        RuntimeProperties runtime = properties.getRuntime();
        // 启动流程最多执行七次限时 Docker 命令，并为端口等待和定时清理保留窗口
        instance.setExpiresAt(TimeUtil.now().plus(runtime.getCommandTimeout().multipliedBy(7))
                .plus(runtime.getStartTimeout()).plus(runtime.getCleanupInterval()));
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
        if (instance.getContainerPort() == null) {
            return false;
        }
        try {
            DockerCliUtil.ContainerInfo info = DockerCliUtil.inspect(runtime.getDockerBinary(),
                    blank(instance.getContainerId()) ? instance.getContainerName() : instance.getContainerId(),
                    runtime.getNetwork(), instance.getContainerPort(),
                    runtime.getCommandTimeout());
            return "running".equalsIgnoreCase(info.state());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 避免查询请求反复等待 Docker；仅恢复超出正常命令预算的启动记录。 */
    private void reconcileStartup(ChallengeContainerInstance instance, RuntimeProperties runtime) {
        ContainerStatus status = instance.getStatus();
        if (status != ContainerStatus.PENDING && status != ContainerStatus.PULLING
                && status != ContainerStatus.STARTING) {
            return;
        }

        Duration staleAfter = switch (status) {
            case PENDING -> runtime.getCleanupInterval().multipliedBy(2);
            case PULLING -> runtime.getCommandTimeout().multipliedBy(3)
                    .plus(runtime.getCleanupInterval().multipliedBy(2));
            case STARTING -> runtime.getCommandTimeout().multipliedBy(3).plus(runtime.getStartTimeout())
                    .plus(runtime.getCleanupInterval().multipliedBy(2));
            default -> Duration.ZERO;
        };
        if (instance.getUpdatedAt() != null
                && TimeUtil.now().isBefore(instance.getUpdatedAt().plus(staleAfter))) {
            return;
        }

        if (status != ContainerStatus.STARTING || instance.getContainerPort() == null) {
            markStatus(instance, ContainerStatus.FAILED, "靶机启动超时，请重试");
            return;
        }

        try {
            // 1. 使用短超时检查稳定容器名，避免状态轮询被 Docker CLI 长时间占用
            DockerCliUtil.ContainerInfo info = DockerCliUtil.inspect(runtime.getDockerBinary(),
                    instance.getContainerName(), runtime.getNetwork(), instance.getContainerPort(),
                    runtime.getCommandTimeout().compareTo(Duration.ofSeconds(5)) > 0
                            ? Duration.ofSeconds(5) : runtime.getCommandTimeout());
            if (!"running".equalsIgnoreCase(info.state()) || blank(info.containerId())
                    || !DockerCliUtil.waitForPort(info.containerIp(), instance.getContainerPort(),
                    runtime.getHealthInterval(), runtime.getHealthInterval())) {
                markStatus(instance, ContainerStatus.FAILED, "靶机启动超时，请重试");
                return;
            }

            // 2. 端口可连接后补齐地址并开始计算完整运行时长
            applyContainerInfo(instance, info);
            instance.setStatus(ContainerStatus.READY);
            instance.setReadyAt(TimeUtil.now());
            instance.setExpiresAt(TimeUtil.now().plus(runtime.getInstanceTtl()));
            instance.setUpdatedAt(TimeUtil.now());
            instanceMapper.updateById(instance);
        } catch (RuntimeException exception) {
            markFailed(instance, exception);
        }
    }

    /** 将 Docker 检查结果写入实例。 */
    private void applyContainerInfo(ChallengeContainerInstance instance, DockerCliUtil.ContainerInfo info) {
        instance.setContainerId(info.containerId());
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

    /** 保存可公开的失败信息，禁止将 Docker 原始输出写入选手可见状态。 */
    private void markFailed(ChallengeContainerInstance instance, RuntimeException exception) {
        String message = exception instanceof ContainerRuntimeException ? exception.getMessage()
                : "题目容器启动失败，请重试或联系管理员检查运行环境";
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

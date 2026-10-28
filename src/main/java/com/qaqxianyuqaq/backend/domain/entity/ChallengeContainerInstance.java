package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.ContainerStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 队伍题目容器实例：同一赛事队伍针对同一题目只保留一个运行实例。 */
@Getter
@Setter
@TableName("challenge_container_instances")
public class ChallengeContainerInstance {

    @TableId(type = IdType.INPUT)
    private String id;

    private String eventId;

    private String challengeId;

    private String teamId;

    private String containerId;

    private String containerName;

    private ContainerStatus status;

    private String dockerImage;

    private String dockerTag;

    private String flagEnvName;

    /** 本队实际 Flag 的 AES-GCM 密文，禁止明文落库或写入日志。 */
    private String flagSecret;

    private String containerIp;

    private String hostIp;

    private Integer hostPort;

    private Integer containerPort;

    private String lastError;

    private LocalDateTime readyAt;

    /** 容器最晚存活时间，到期后由定时清理任务销毁。 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

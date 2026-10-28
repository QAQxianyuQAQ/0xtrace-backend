package com.qaqxianyuqaq.backend.domain.enums;

/** 队伍题目容器的生命周期状态。 */
public enum ContainerStatus {
    PENDING,
    PULLING,
    STARTING,
    READY,
    FAILED,
    STOPPED
}

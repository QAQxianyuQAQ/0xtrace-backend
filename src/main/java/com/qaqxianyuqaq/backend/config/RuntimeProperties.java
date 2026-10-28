package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/** Docker 题目运行时配置：仅支持单台 Docker Engine。 */
@Getter
@Setter
public class RuntimeProperties {

    /** 是否允许启动真实题目容器，自动化测试默认关闭。 */
    private boolean enabled = true;

    /** Docker CLI 可执行文件。 */
    private String dockerBinary = "docker";

    /** 题目容器加入的内部网络，不向公网发布端口。 */
    private String network = "tracequest-runtime";

    /** Docker 命令超时，避免拉取或创建过程无限阻塞。 */
    private Duration commandTimeout = Duration.ofMinutes(5);

    /** 容器启动后等待端口可连接的时长。 */
    private Duration startTimeout = Duration.ofSeconds(60);

    /** 容器健康检查间隔。 */
    private Duration healthInterval = Duration.ofSeconds(1);

    /** 新建容器的初始存活时间。 */
    private Duration instanceTtl = Duration.ofHours(1);

    /** 每次延长增加的存活时间。 */
    private Duration extensionDuration = Duration.ofHours(1);

    /** 仅当剩余时间不超过该阈值时允许延长。 */
    private Duration extensionWindow = Duration.ofMinutes(30);

    /** 过期容器清理任务间隔。 */
    private Duration cleanupInterval = Duration.ofSeconds(30);

    /** 单容器内存上限。 */
    private String memoryLimit = "512m";

    /** 单容器 CPU 上限。 */
    private String cpuLimit = "1.0";

    /** 单容器进程数上限。 */
    private long pidsLimit = 256;

    /** WebSocket 连接票据有效期。 */
    private Duration connectionTicketTtl = Duration.ofSeconds(60);

    /** WebSocket 空闲断开时间。 */
    private Duration websocketIdleTimeout = Duration.ofMinutes(5);

    /** 单个 WebSocket 二进制消息最大字节数。 */
    private int maxWebsocketFrameBytes = 1024 * 1024;

    /** 公网 WSS 地址，由 frp 或反向代理提供。 */
    private String publicWssUrl = "wss://localhost/api/v1/challenge-connections";
}

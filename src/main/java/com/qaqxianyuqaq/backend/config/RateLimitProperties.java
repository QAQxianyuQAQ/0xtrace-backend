package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/** 限流参数（对应 tracequest.rate-limit）。 */
@Getter
@Setter
public class RateLimitProperties {

    private int loginPerMinute = 10;

    private int loginFailureThreshold = 8;

    private Duration loginFailureWindow = Duration.ofMinutes(15);

    private int submissionPerMinute = 30;

    private int locationVerifyPerMinute = 20;

    private int adminWritePerMinute = 60;

    /** 单个账号每分钟允许启动题目容器的次数。 */
    private int containerStartPerMinute = 6;

    /** 单个账号每分钟允许申请连接票据的次数。 */
    private int containerTicketPerMinute = 30;
}

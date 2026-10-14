package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

/** 初始化数据参数（对应 tracequest.bootstrap），仅用于本地与测试环境。 */
@Getter
@Setter
public class BootstrapProperties {

    /** 是否在启动时创建默认管理员与赛事。 */
    private boolean enabled = true;

    private String adminUsername = "admin";

    /** 初始管理员密码，由环境变量 TQ_ADMIN_PASSWORD 注入（仅初始化时使用）。 */
    private String adminPassword;

    private String eventName = "TraceQuest Season 01";

    private String eventTimezone = "Asia/Shanghai";
}

package com.qaqxianyuqaq.backend.domain.enums;

/** 选手账号状态：active 可登录，disabled 停用且会话失效。 */
public enum AccountStatus {
    ACTIVE,
    DISABLED;

    /** 转换为对外暴露的小写状态字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

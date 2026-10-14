package com.qaqxianyuqaq.backend.domain.enums;

/** 赛事状态：仅允许 setup → open → paused → open → closed 的受限流转。 */
public enum EventStatus {
    SETUP,
    OPEN,
    PAUSED,
    CLOSED;

    /** 转换为对外暴露的小写状态字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

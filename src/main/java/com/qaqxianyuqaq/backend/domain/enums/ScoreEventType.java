package com.qaqxianyuqaq.backend.domain.enums;

/** 积分事件类型：challenge_solve 为题目得分，admin_adjustment 为后台手工调整。 */
public enum ScoreEventType {
    CHALLENGE_SOLVE,
    ADMIN_ADJUSTMENT;

    /** 转换为对外暴露的小写事件类型字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

package com.qaqxianyuqaq.backend.domain.enums;

/** 题目状态：draft 未发布，published 选手可见，paused 暂停作答，archived 归档。 */
public enum ChallengeStatus {
    DRAFT,
    PUBLISHED,
    PAUSED,
    ARCHIVED;

    /** 转换为对外暴露的小写状态字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }

    /** 判断选手端是否可见该题目。 */
    public boolean visibleToContestant() {
        return this == PUBLISHED || this == PAUSED;
    }

    /** 判断题目当前是否允许提交作答。 */
    public boolean answerable() {
        return this == PUBLISHED;
    }
}

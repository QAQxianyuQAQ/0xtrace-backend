package com.qaqxianyuqaq.backend.domain.enums;

/** 判题结果：correct 表示答案正确（含幂等重放），incorrect 表示答案错误。 */
public enum SubmissionResult {
    CORRECT,
    INCORRECT;

    /** 转换为对外暴露的小写结果字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

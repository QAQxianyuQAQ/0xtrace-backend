package com.qaqxianyuqaq.backend.domain.enums;

/** 完成类型：first_solve 为首次解出并计分，already_solved 为重复提交的幂等结果。 */
public enum CompletionType {
    FIRST_SOLVE,
    ALREADY_SOLVED;

    /** 转换为对外暴露的小写完成类型字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

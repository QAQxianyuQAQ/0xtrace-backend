package com.qaqxianyuqaq.backend.domain.enums;

/** 文件用途：attachment 为题目附件，cover 为题目封面（封面在同一题内唯一）。 */
public enum FileKind {
    ATTACHMENT,
    COVER;

    /** 转换为对外暴露的小写用途字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

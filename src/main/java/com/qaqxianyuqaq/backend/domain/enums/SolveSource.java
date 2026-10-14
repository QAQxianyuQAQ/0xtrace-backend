package com.qaqxianyuqaq.backend.domain.enums;

/** 解题来源：answer 为文本/Flag 提交判题，location 为现场二维码核验。 */
public enum SolveSource {
    ANSWER,
    LOCATION;

    /** 转换为对外暴露的小写来源字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

package com.qaqxianyuqaq.backend.domain.enums;

/** 账号角色：对应接口契约中的 public/contestant/admin 三类权限来源。 */
public enum UserRole {
    ADMIN,
    CONTESTANT;

    /** 转换为对外暴露的小写角色字符串。 */
    public String apiValue() {
        return name().toLowerCase();
    }
}

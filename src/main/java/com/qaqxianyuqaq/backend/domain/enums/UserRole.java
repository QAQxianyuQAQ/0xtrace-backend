package com.qaqxianyuqaq.backend.domain.enums;

/** 账号角色：最高管理员与普通管理员对外统一暴露为管理员。 */
public enum UserRole {
    ADMIN,
    SUPER_ADMIN,
    CONTESTANT;

    /** 转换为对外暴露的小写角色字符串。 */
    public String apiValue() {
        return this == SUPER_ADMIN ? "admin" : name().toLowerCase();
    }
}

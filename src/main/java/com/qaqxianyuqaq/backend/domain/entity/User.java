package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 账号实体：管理员与选手共用一张表，通过 role 区分权限。 */
@Getter
@Setter
@TableName("users")
public class User {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 登录用户名，全局唯一。 */
    private String username;

    /** BCrypt 密码哈希，禁止任何接口返回该字段。 */
    private String passwordHash;

    private UserRole role;

    private AccountStatus status;

    /** 所属队伍主键，允许为空（未组队）。 */
    private String teamId;

    /** 后台备注，仅管理端可见。 */
    private String note;

    private String nickname;

    private String avatarUrl;

    /** 会话版本号：停用账号或重置密码时自增，使已签发令牌立即失效。 */
    private Integer tokenVersion;

    private LocalDateTime createdAt;

    private LocalDateTime lastLoginAt;
}

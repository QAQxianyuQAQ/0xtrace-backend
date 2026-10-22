package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 赛事内队伍实体：同一账号可以在不同赛事加入不同队伍。 */
@Getter
@Setter
@TableName("teams")
public class Team {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 队伍所属赛事，统计主体只在赛事内有效。 */
    private String eventId;

    private String name;

    /** 队友邀请码，管理员新建队伍时也会生成唯一码。 */
    private String joinCode;

    /** 创建队伍的账号标识。 */
    private String createdBy;

    private String note;

    private LocalDateTime createdAt;
}

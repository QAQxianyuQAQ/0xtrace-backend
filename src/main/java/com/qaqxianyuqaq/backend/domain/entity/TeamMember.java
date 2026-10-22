package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 赛事队伍成员关系实体：记录成员在单场赛事中的队伍归属。 */
@Getter
@Setter
@TableName("team_members")
public class TeamMember {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 成员关系所属赛事，用于限制同一账号只加入一个赛事队伍。 */
    private String eventId;

    private String teamId;

    private String userId;

    private LocalDateTime joinedAt;
}

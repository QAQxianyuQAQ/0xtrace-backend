package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 队伍成员关系实体：记录成员加入队伍的审计信息。 */
@Getter
@Setter
@TableName("team_members")
public class TeamMember {

    @TableId(type = IdType.INPUT)
    private String id;

    private String teamId;

    private String userId;

    private LocalDateTime joinedAt;
}

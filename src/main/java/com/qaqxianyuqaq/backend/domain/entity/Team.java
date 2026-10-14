package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 队伍实体：排行榜按队伍维度统计时的统计主体。 */
@Getter
@Setter
@TableName("teams")
public class Team {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    private String note;

    private LocalDateTime createdAt;
}

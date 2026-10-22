package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 赛事配置实体：赛事名称、状态、时区、起止时间与队伍人数上限。 */
@Getter
@Setter
@TableName("events")
public class EventConfig {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    private EventStatus status;

    /** IANA 时区标识，例如 Asia/Shanghai。 */
    private String timezone;

    private LocalDateTime startsAt;

    private LocalDateTime endsAt;

    /** 每个队伍允许加入的最大成员数，创建赛事时确定。 */
    private Integer maxTeamSize;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

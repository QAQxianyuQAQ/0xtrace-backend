package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 赛事配置实体：当前赛事名称、状态、时区与起止时间。 */
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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

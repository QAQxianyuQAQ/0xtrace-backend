package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 手工积分调整实体：必须记录原因与操作者，用于赛事复盘。 */
@Getter
@Setter
@TableName("score_adjustments")
public class ScoreAdjustment {

    @TableId(type = IdType.INPUT)
    private String id;

    private String teamId;

    private String userId;

    /** 调整分值，非零整数，正数为加分、负数为扣分。 */
    private Integer delta;

    private String reason;

    private String adminId;

    private LocalDateTime createdAt;
}

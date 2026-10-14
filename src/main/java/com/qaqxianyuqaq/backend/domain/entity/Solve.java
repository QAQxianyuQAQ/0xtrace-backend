package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.SolveSource;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 解题记录实体：同一队伍（或个人）与题目唯一一行，是计分与名次的权威来源。 */
@Getter
@Setter
@TableName("solves")
public class Solve {

    @TableId(type = IdType.INPUT)
    private String id;

    private String userId;

    private String teamId;

    /** 统计主体键：有队伍时为队伍 ID，否则为用户 ID，用于唯一约束与计分归属。 */
    private String subjectKey;

    private String challengeId;

    private String submissionId;

    private LocalDateTime solvedAt;

    /** 解题名次：1 为首解，依次递增。 */
    private Integer solvePlace;

    private Integer basePoints;

    private Integer bonusPoints;

    private SolveSource source;

    /** 幂等键，用于同一 Idempotency-Key 重放时返回原结果。 */
    private String idempotencyKey;
}

package com.qaqxianyuqaq.backend.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.qaqxianyuqaq.backend.domain.enums.SolveSource;
import com.qaqxianyuqaq.backend.domain.enums.SubmissionResult;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 提交记录实体：保存判题结果与请求幂等键，用于审计与重复提交排查。 */
@Getter
@Setter
@TableName("submissions")
public class Submission {

    @TableId(type = IdType.INPUT)
    private String id;

    private String userId;

    private String teamId;

    private String challengeId;

    /** 提交内容的 SHA-256 摘要，不落明文答案。 */
    private String answerDigest;

    private SubmissionResult result;

    /** 本次提交实际产生的积分（错误或重复提交为 0）。 */
    private Integer pointsAwarded;

    private Integer solvePlace;

    private SolveSource source;

    /** 客户端生成的请求 ID，用于幂等重放定位。 */
    private String requestId;

    private LocalDateTime submittedAt;
}

package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 排行榜聚合查询的行模型，由持久层直接映射。 */
@Getter
@Setter
public class LeaderboardRowVO {

    /** 统计主体：队伍模式下为队伍 ID，个人模式下为用户 ID。 */
    private String subjectId;

    private String subjectName;

    private Integer solvedCount;

    /** 已含手工调整的总分。 */
    private Integer score;

    private LocalDateTime lastSolvedAt;
}

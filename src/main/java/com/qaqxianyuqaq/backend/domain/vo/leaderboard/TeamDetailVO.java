package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

import java.util.List;

/** 队伍积分明细响应。 */
public record TeamDetailVO(TeamScoreVO team, List<ScoreEventVO> scoreEvents) {
}

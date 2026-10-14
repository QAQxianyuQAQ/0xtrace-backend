package com.qaqxianyuqaq.backend.service.leaderboard;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.leaderboard.ScoreAdjustmentDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardItemVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.ScoreEventVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.TeamDetailVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 排行榜与计分管理服务。 */
public interface LeaderboardService {

    /** 公开榜单，登录后返回 isMe 标记。 */
    LeaderboardVO publicLeaderboard(String scope, UserPrincipal viewer, PageQueryDTO query);

    /** 后台榜单，支持关键字筛选。 */
    PageVO<LeaderboardItemVO> adminLeaderboard(String scope, String q, PageQueryDTO query);

    /** 队伍积分明细与解题时间线。 */
    TeamDetailVO teamDetail(String teamId);

    /** 查询题目得分与手工调整事件。 */
    PageVO<ScoreEventVO> scoreEvents(String teamId, String userId, String type, PageQueryDTO query);

    /** 手工调整积分，必须填写原因并落审计。 */
    ScoreEventVO adjustScore(ScoreAdjustmentDTO request, UserPrincipal operator);
}

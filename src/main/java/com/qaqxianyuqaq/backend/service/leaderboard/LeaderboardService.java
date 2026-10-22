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
    LeaderboardVO publicLeaderboard(String scope, String eventId, UserPrincipal viewer, PageQueryDTO query);

    /** 后台榜单，支持关键字筛选。 */
    PageVO<LeaderboardItemVO> adminLeaderboard(String scope, String eventId, String q, PageQueryDTO query);

    /** 队伍积分明细、全部成员与分页提交日志。 */
    TeamDetailVO teamDetail(String eventId, String teamId, PageQueryDTO query);

    /** 查询题目得分与手工调整事件。 */
    PageVO<ScoreEventVO> scoreEvents(String eventId, String teamId, String userId,
                                     String type, PageQueryDTO query);

    /** 手工调整积分，必须填写原因并落审计。 */
    ScoreEventVO adjustScore(ScoreAdjustmentDTO request, UserPrincipal operator);
}

package com.qaqxianyuqaq.backend.controller.leaderboard;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.leaderboard.ScoreAdjustmentDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardItemVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.ScoreEventVO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.TeamDetailVO;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.leaderboard.LeaderboardService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台排行榜与计分管理接口。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminLeaderboardController {

    private final LeaderboardService leaderboardService;

    public AdminLeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    /** 完整排行榜，支持队伍/个人维度与关键字筛选。 */
    @GetMapping("/leaderboard")
    public PageVO<LeaderboardItemVO> leaderboard(
            @RequestParam(required = false, defaultValue = "team") String scope,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return leaderboardService.adminLeaderboard(scope, q, query);
    }

    /** 队伍积分明细与解题时间线。 */
    @GetMapping("/leaderboard/{teamId}")
    public TeamDetailVO teamDetail(@PathVariable String teamId) {
        return leaderboardService.teamDetail(teamId);
    }

    /** 查询加分/扣分及题目得分事件。 */
    @GetMapping("/score-events")
    public PageVO<ScoreEventVO> scoreEvents(@RequestParam(required = false) String teamId,
                                                           @RequestParam(required = false) String userId,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer pageSize) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return leaderboardService.scoreEvents(teamId, userId, type, query);
    }

    /** 手工调整积分，必须填写原因并落审计。 */
    @PostMapping("/score-adjustments")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "score.adjusted", entityType = "score-adjustment")
    public ResponseEntity<ScoreEventVO> adjustScore(
            @Valid @RequestBody ScoreAdjustmentDTO request,
            @AuthenticationPrincipal UserPrincipal principal) {
        ScoreEventVO event = leaderboardService.adjustScore(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(event);
    }
}

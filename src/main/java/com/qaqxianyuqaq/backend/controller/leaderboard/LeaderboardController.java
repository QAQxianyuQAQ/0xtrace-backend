package com.qaqxianyuqaq.backend.controller.leaderboard;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardVO;

import com.qaqxianyuqaq.backend.security.SecurityUtils;
import com.qaqxianyuqaq.backend.service.leaderboard.LeaderboardService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公开排行榜接口。 */
@RestController
@RequestMapping("/api/v1/leaderboard")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    /** 按赛事分页返回队伍排名；登录后返回 isMe 标记。 */
    @GetMapping
    public LeaderboardVO list(@RequestParam(required = false, defaultValue = "team") String scope,
                                              @RequestParam(required = false) String eventId,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer pageSize) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return leaderboardService.publicLeaderboard(scope, eventId,
                SecurityUtils.currentPrincipalOrNull(), query);
    }
}

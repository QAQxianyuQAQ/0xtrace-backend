package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

import com.qaqxianyuqaq.backend.domain.vo.common.PaginationVO;

import java.time.LocalDateTime;
import java.util.List;

/** 公开榜单响应。 */
public record LeaderboardVO(LocalDateTime updatedAt, LeaderboardStatsVO stats, List<LeaderboardItemVO> items,
                            PaginationVO pagination) {
}

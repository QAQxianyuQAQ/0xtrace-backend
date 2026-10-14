package com.qaqxianyuqaq.backend.domain.dto.leaderboard;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 手工积分调整请求。 */
public record ScoreAdjustmentDTO(
        @Size(max = 64, message = "队伍 ID 过长") String teamId,
        @Size(max = 64, message = "用户 ID 过长") String userId,
        @NotNull(message = "调整分值不能为空") Integer delta,
        @Size(max = 512, message = "调整原因过长") String reason) {
}

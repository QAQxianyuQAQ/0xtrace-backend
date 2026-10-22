package com.qaqxianyuqaq.backend.domain.vo.leaderboard;

import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMemberVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.AdminSubmissionLogVO;

import java.util.List;

/** 后台队伍详情：积分、全部成员与对应提交记录。 */
public record TeamDetailVO(TeamScoreVO team, List<TeamMemberVO> members,
                           PageVO<AdminSubmissionLogVO> submissionLogs, List<ScoreEventVO> scoreEvents) {
}

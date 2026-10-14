package com.qaqxianyuqaq.backend.repository.submission;

import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardRowVO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 解题记录持久化接口，提供计分与排行榜聚合查询。 */
public interface SolveMapper extends BaseMapper<Solve> {

    /**
     * 队伍维度排行榜聚合：总分降序、解题数降序、最后解题时间升序。
     *
     * @param page    分页参数
     * @param keyword 队伍名称模糊搜索关键字，可为空
     */
    @Select("""
            <script>
            SELECT base.subject_id AS subjectId,
                   base.subject_name AS subjectName,
                   base.solved_count AS solvedCount,
                   base.score + COALESCE(adj.delta, 0) AS score,
                   base.last_solved_at AS lastSolvedAt
            FROM (
                SELECT s.team_id AS subject_id, t.name AS subject_name,
                       COUNT(*) AS solved_count,
                       SUM(s.base_points + s.bonus_points) AS score,
                       MAX(s.solved_at) AS last_solved_at
                FROM solves s
                JOIN teams t ON t.id = s.team_id
                <if test="keyword != null and keyword != ''">
                    WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                </if>
                GROUP BY s.team_id, t.name
            ) base
            LEFT JOIN (
                SELECT team_id AS subject_id, SUM(delta) AS delta
                FROM score_adjustments
                WHERE team_id IS NOT NULL
                GROUP BY team_id
            ) adj ON adj.subject_id = base.subject_id
            ORDER BY score DESC, solvedCount DESC, lastSolvedAt ASC
            </script>
            """)
    IPage<LeaderboardRowVO> selectTeamLeaderboard(IPage<LeaderboardRowVO> page, @Param("keyword") String keyword);

    /**
     * 个人维度排行榜聚合：仅统计选手角色账号。
     *
     * @param page    分页参数
     * @param keyword 用户名字模糊搜索关键字，可为空
     */
    @Select("""
            <script>
            SELECT base.subject_id AS subjectId,
                   base.subject_name AS subjectName,
                   base.solved_count AS solvedCount,
                   base.score + COALESCE(adj.delta, 0) AS score,
                   base.last_solved_at AS lastSolvedAt
            FROM (
                SELECT s.user_id AS subject_id, u.username AS subject_name,
                       COUNT(*) AS solved_count,
                       SUM(s.base_points + s.bonus_points) AS score,
                       MAX(s.solved_at) AS last_solved_at
                FROM solves s
                JOIN users u ON u.id = s.user_id
                WHERE u.role = 'CONTESTANT'
                <if test="keyword != null and keyword != ''">
                    AND LOWER(u.username) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                </if>
                GROUP BY s.user_id, u.username
            ) base
            LEFT JOIN (
                SELECT user_id AS subject_id, SUM(delta) AS delta
                FROM score_adjustments
                WHERE user_id IS NOT NULL
                GROUP BY user_id
            ) adj ON adj.subject_id = base.subject_id
            ORDER BY score DESC, solvedCount DESC, lastSolvedAt ASC
            </script>
            """)
    IPage<LeaderboardRowVO> selectIndividualLeaderboard(IPage<LeaderboardRowVO> page,
                                                       @Param("keyword") String keyword);
}

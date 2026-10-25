package com.qaqxianyuqaq.backend.repository.submission;

import com.qaqxianyuqaq.backend.domain.vo.leaderboard.LeaderboardRowVO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qaqxianyuqaq.backend.domain.entity.Solve;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 解题记录持久化接口，提供计分与排行榜聚合查询。 */
public interface SolveMapper extends BaseMapper<Solve> {
    /** 进度和榜单共用同一赛事队伍计分口径，包含人工调整。 */
    @Select("""
            SELECT COALESCE((SELECT SUM(s.base_points + s.bonus_points)
              FROM solves s JOIN challenges c ON c.id=s.challenge_id
              WHERE c.event_id=#{eventId} AND s.team_id=#{teamId}), 0)
              + COALESCE((SELECT SUM(a.delta) FROM score_adjustments a
              JOIN teams t ON t.id=a.team_id
              WHERE t.event_id=#{eventId} AND a.team_id=#{teamId}), 0)
            """)
    long selectTeamScore(@Param("eventId") String eventId, @Param("teamId") String teamId);


    /**
     * 队伍维度排行榜聚合：总分降序、解题数降序、最后解题时间升序。
     *
     * @param page    分页参数
     * @param eventId 赛事标识，排名只在单场赛事内计算
     * @param keyword 队伍名称模糊搜索关键字，可为空
     */
    @Select("""
            <script>
            SELECT t.id AS subjectId,
                   t.name AS subjectName,
                   COALESCE(base.solved_count, 0) AS solvedCount,
                   COALESCE(base.score, 0) + COALESCE(adj.delta, 0) AS score,
                   base.last_solved_at AS lastSolvedAt
            FROM teams t
            LEFT JOIN (
                SELECT s.team_id AS subject_id,
                       COUNT(*) AS solved_count,
                       SUM(s.base_points + s.bonus_points) AS score,
                       MAX(s.solved_at) AS last_solved_at
                FROM solves s
                JOIN challenges c ON c.id = s.challenge_id
                WHERE c.event_id = #{eventId}
                GROUP BY s.team_id
            ) base ON base.subject_id = t.id
            LEFT JOIN (
                SELECT team_id AS subject_id, SUM(delta) AS delta
                FROM score_adjustments
                WHERE team_id IS NOT NULL
                GROUP BY team_id
            ) adj ON adj.subject_id = t.id
            WHERE t.event_id = #{eventId}
            <if test="keyword != null and keyword != ''">
                AND LOWER(t.name) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY score DESC, solvedCount DESC, lastSolvedAt ASC, t.created_at ASC
            </script>
            """)
    IPage<LeaderboardRowVO> selectTeamLeaderboard(IPage<LeaderboardRowVO> page,
                                                  @Param("eventId") String eventId,
                                                  @Param("keyword") String keyword);

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

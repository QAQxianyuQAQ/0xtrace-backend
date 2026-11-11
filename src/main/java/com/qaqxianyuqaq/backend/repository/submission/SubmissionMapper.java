package com.qaqxianyuqaq.backend.repository.submission;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qaqxianyuqaq.backend.domain.entity.Submission;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.domain.dto.admin.AdminSubmissionQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.submission.ManagedSubmissionVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;

/** 提交记录持久化接口。 */
public interface SubmissionMapper extends BaseMapper<Submission> {
    /** 以提交时的队伍归属查询，不随成员当前所在队伍改变。 */
    @Select("""
        <script>
        SELECT s.id AS submission_id, c.event_id, s.user_id, u.username, u.nickname,
               s.team_id, t.name AS team_name, c.code AS challenge_code, c.title AS challenge_title,
               LOWER(s.result) AS result, s.points_awarded, s.solve_place, LOWER(s.source) AS source,
               s.request_id, s.submitted_at
        FROM submissions s JOIN challenges c ON c.id = s.challenge_id
        LEFT JOIN users u ON u.id = s.user_id LEFT JOIN teams t ON t.id = s.team_id
        WHERE c.event_id = #{eventId}
        <if test="filter.teamId != null and filter.teamId != ''">AND s.team_id = #{filter.teamId}</if>
        <if test="filter.userId != null and filter.userId != ''">AND s.user_id = #{filter.userId}</if>
        <if test="filter.challengeCode != null and filter.challengeCode != ''">AND c.code = #{filter.challengeCode}</if>
        <if test="filter.result != null and filter.result != ''">AND s.result = UPPER(#{filter.result})</if>
        <if test="filter.source != null and filter.source != ''">AND s.source = UPPER(#{filter.source})</if>
        <if test="filter.requestId != null and filter.requestId != ''">AND s.request_id = #{filter.requestId}</if>
        <if test="from != null">AND s.submitted_at &gt;= #{from}</if>
        <if test="to != null">AND s.submitted_at &lt;= #{to}</if>
        ORDER BY s.submitted_at DESC, s.id DESC
        </script>
        """)
    IPage<ManagedSubmissionVO> adminPage(Page<ManagedSubmissionVO> page, @Param("eventId") String eventId,
            @Param("filter") AdminSubmissionQueryDTO filter, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

}

package com.qaqxianyuqaq.backend.service.admin.impl;

import com.qaqxianyuqaq.backend.domain.vo.admin.ActivityVO;
import com.qaqxianyuqaq.backend.domain.dto.admin.AuditLogQueryDTO;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.domain.vo.admin.DashboardVO;
import com.qaqxianyuqaq.backend.domain.vo.admin.MetricsVO;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.repository.challenge.ChallengeMapper;
import com.qaqxianyuqaq.backend.repository.submission.SolveMapper;
import com.qaqxianyuqaq.backend.repository.submission.SubmissionMapper;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.service.audit.AuditService;
import com.qaqxianyuqaq.backend.service.admin.DashboardService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import org.springframework.stereotype.Service;

import java.util.List;

/** 后台总览服务实现。 */
@Service
public class DashboardServiceImpl implements DashboardService {

    /** 总览页展示的近期活动条数。 */
    private static final int RECENT_ACTIVITY_LIMIT = 10;

    private final ChallengeMapper challengeMapper;

    private final UserMapper userMapper;

    private final SolveMapper solveMapper;

    private final SubmissionMapper submissionMapper;

    private final AuditService auditService;

    private final EventService eventService;

    private final PermissionAssert permissionAssert;

    public DashboardServiceImpl(ChallengeMapper challengeMapper, UserMapper userMapper, SolveMapper solveMapper,
                                SubmissionMapper submissionMapper, AuditService auditService,
                                EventService eventService, PermissionAssert permissionAssert) {
        this.challengeMapper = challengeMapper;
        this.userMapper = userMapper;
        this.solveMapper = solveMapper;
        this.submissionMapper = submissionMapper;
        this.auditService = auditService;
        this.eventService = eventService;
        this.permissionAssert = permissionAssert;
    }

    @Override
    public DashboardVO dashboard(UserPrincipal principal) {
        // 1. 服务层也验证管理权限，不通过总览旁路泄露完整审计
        permissionAssert.assertAdmin(principal);
        // 1. 统计题目数量与上线题目数量
        int challengeCount = Math.toIntExact(challengeMapper.selectCount(null));
        int publishedCount = Math.toIntExact(challengeMapper.selectCount(Wrappers.<Challenge>lambdaQuery()
                .eq(Challenge::getStatus, ChallengeStatus.PUBLISHED)));

        // 2. 统计账号数量与启用账号数量
        int contestantCount = Math.toIntExact(userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, UserRole.CONTESTANT)));
        int activeContestantCount = Math.toIntExact(userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, UserRole.CONTESTANT)
                .eq(User::getStatus, AccountStatus.ACTIVE)));

        // 3. 统计解题数与提交数
        int solvedCount = Math.toIntExact(solveMapper.selectCount(null));
        int submissionCount = Math.toIntExact(submissionMapper.selectCount(null));

        // 4. 读取近期活动摘要（审计日志最近 10 条）
        List<ActivityVO> activities = principal.isSuperAdmin() ? auditService
                .page(new AuditLogQueryDTO(null, null, null, null, null, null, null, null),
                        PageUtil.normalize(1, RECENT_ACTIVITY_LIMIT), principal)
                .items().stream()
                .map(log -> new ActivityVO(log.id(), log.actor(), log.action(), log.entityType(),
                        log.entityId(), log.createdAt()))
                .toList() : List.of();

        // 5. 组装总览响应
        MetricsVO metrics = new MetricsVO(challengeCount, publishedCount, contestantCount,
                activeContestantCount, solvedCount, submissionCount);
        return new DashboardVO(eventService.publicEvent(), metrics, activities);
    }
}

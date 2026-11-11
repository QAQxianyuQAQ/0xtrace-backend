package com.qaqxianyuqaq.backend.service.contestant.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qaqxianyuqaq.backend.aspect.DistributedLock;
import com.qaqxianyuqaq.backend.service.audit.AuditService;
import com.qaqxianyuqaq.backend.util.AuditSnapshotUtil;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamJoinDTO;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.TeamMember;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMembershipVO;
import com.qaqxianyuqaq.backend.domain.vo.admin.ManagedTeamVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamBriefVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMemberVO;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.event.EventMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMemberMapper;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TeamCodeGenerator;
import com.qaqxianyuqaq.backend.util.PageUtil;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/** 赛事队伍服务实现。 */
@Service
public class TeamServiceImpl implements TeamService {

    private final TeamMapper teamMapper;

    private final TeamMemberMapper teamMemberMapper;

    private final UserMapper userMapper;

    private final EventMapper eventMapper;

    private final PermissionAssert permissionAssert;

    private final AuditService auditService;

    public TeamServiceImpl(TeamMapper teamMapper, TeamMemberMapper teamMemberMapper, UserMapper userMapper,
                           EventMapper eventMapper, PermissionAssert permissionAssert, AuditService auditService) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.userMapper = userMapper;
        this.eventMapper = eventMapper;
        this.permissionAssert = permissionAssert;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    @DistributedLock(key = "'team-create:' + #p0 + ':' + #p2.userId()")
    public TeamMembershipVO createTeam(String eventId, TeamCreateDTO request, UserPrincipal principal) {
        // 1. 校验选手身份与赛事当前允许组队
        permissionAssert.assertContestant(principal);
        EventConfig event = requireJoinableEvent(eventId);

        // 2. 校验同一选手在该赛事尚未加入其他队伍
        requireNoMembership(eventId, principal.userId());

        // 3. 校验赛事内队伍名称唯一
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isBlank()) {
            throw new ValidationFailedException("name", "队伍名称不能为空");
        }
        boolean duplicated = teamMapper.exists(Wrappers.<Team>lambdaQuery()
                .eq(Team::getEventId, eventId)
                .eq(Team::getName, name));
        if (duplicated) {
            throw new ConflictException("该赛事已存在同名队伍");
        }

        // 4. 创建队伍并生成队友加入所需的唯一标识码
        Team team = new Team();
        team.setId(IdGenerator.next("team"));
        team.setEventId(eventId);
        team.setName(name);
        team.setJoinCode(TeamCodeGenerator.next());
        team.setCreatedBy(principal.userId());
        team.setCreatedAt(TimeUtil.now());
        teamMapper.insert(team);

        // 5. 将创建者登记为队伍成员，使其立即以队伍维度参赛
        insertMembership(eventId, team.getId(), principal.userId());

        // 6. 与成员关系在同一事务内保存变动摘要，审计失败则回滚本次组队
        auditService.recordTeamChange("team.created", team.getId(),
                AuditSnapshotUtil.team(eventId, principal.userId(), null),
                AuditSnapshotUtil.team(eventId, principal.userId(), team));
        return toVO(team, event, principal.userId());
    }

    @Override
    @Transactional
    @DistributedLock(key = "'team-join:' + #p0 + ':' + #p1.code()")
    public TeamMembershipVO joinTeam(String eventId, TeamJoinDTO request, UserPrincipal principal) {
        // 1. 校验选手身份与赛事当前允许组队
        permissionAssert.assertContestant(principal);
        EventConfig event = requireJoinableEvent(eventId);

        // 2. 校验同一选手在该赛事尚未加入其他队伍
        requireNoMembership(eventId, principal.userId());

        // 3. 使用规范化标识码定位赛事内队伍，避免跨赛事误加入
        String code = request.code() == null ? "" : request.code().trim().toUpperCase(Locale.ROOT);
        Team team = teamMapper.selectOne(Wrappers.<Team>lambdaQuery()
                .eq(Team::getEventId, eventId)
                .eq(Team::getJoinCode, code));
        if (team == null) {
            throw new NotFoundException("队伍标识码无效");
        }

        // 4. 校验队伍剩余容量并登记成员关系
        assertCapacity(team, event);
        insertMembership(eventId, team.getId(), principal.userId());

        // 5. 记录入队身份与目标队伍，不保存邀请码
        auditService.recordTeamChange("team.joined", team.getId(),
                AuditSnapshotUtil.team(eventId, principal.userId(), null),
                AuditSnapshotUtil.team(eventId, principal.userId(), team));
        return toVO(team, event, principal.userId());
    }

    @Override
    @Transactional
    public void leaveTeam(String eventId, UserPrincipal principal) {
        // 1. 限定为选手本人并核验赛事存在，允许结束后退出但不开放新入队
        permissionAssert.assertContestant(principal);
        requireEvent(eventId);

        // 2. 锁定当前成员关系，再读取原队伍，避免并发改队让前后摘要失真
        TeamMember membership = teamMemberMapper.selectOne(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getEventId, eventId)
                .eq(TeamMember::getUserId, principal.userId()).last("FOR UPDATE"));
        if (membership == null) return;
        Team before = requireEventTeam(eventId, membership.getTeamId());

        // 3. 只删除本人的赛事成员关系，保留原队伍的计分、提交与运行实例
        teamMemberMapper.delete(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getEventId, eventId)
                .eq(TeamMember::getUserId, principal.userId()));

        // 4. 与退出操作原子保存前后队伍归属，不为重复退出编造变更
        auditService.recordTeamChange("team.left", before.getId(),
                AuditSnapshotUtil.team(eventId, principal.userId(), before),
                AuditSnapshotUtil.team(eventId, principal.userId(), null));
    }

    @Override
    public TeamMembershipVO myTeam(String eventId, UserPrincipal principal) {
        // 1. 校验登录身份并读取赛事实体
        permissionAssert.assertAuthenticated(principal);
        EventConfig event = requireEvent(eventId);

        // 2. 查询选手在该赛事的队伍，未加入时明确返回空
        Team team = findMembership(eventId, principal.userId());
        return team == null ? null : toVO(team, event, principal.userId());
    }

    @Override
    public Team findMembership(String eventId, String userId) {
        // 1. 先定位赛事内成员关系，确保队伍归属只对单场赛事生效
        TeamMember member = teamMemberMapper.selectOne(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getEventId, eventId)
                .eq(TeamMember::getUserId, userId)
                .last("LIMIT 1"));
        if (member == null) {
            return null;
        }

        // 2. 读取队伍并校验其仍属于当前赛事
        return requireEventTeam(eventId, member.getTeamId());
    }

    @Override
    public Team requireMembership(String eventId, String userId) {
        // 1. 查询赛事队伍成员关系，未组队时禁止提交和计分
        Team team = findMembership(eventId, userId);
        if (team == null) {
            throw new ConflictException("请先创建或加入当前赛事队伍后再操作");
        }
        return team;
    }

    @Override
    public Team requireEventTeam(String eventId, String teamId) {
        // 1. 按主键读取队伍并校验赛事归属，防止跨赛事访问
        Team team = teamId == null ? null : teamMapper.selectById(teamId);
        if (team == null || !eventId.equals(team.getEventId())) {
            throw new NotFoundException("赛事队伍不存在");
        }
        return team;
    }

    @Override
    @Transactional
    public Team resolveManagedTeam(String eventId, String teamId, String teamName,
                                   boolean createIfAbsent, String creatorId) {
        // 1. 优先按赛事内队伍编号读取
        if (teamId != null && !teamId.isBlank()) {
            return requireEventTeam(eventId, teamId);
        }
        if (teamName == null || teamName.isBlank()) {
            return null;
        }

        // 2. 按赛事内名称查找已有队伍
        String name = teamName.trim();
        Team existing = teamMapper.selectOne(Wrappers.<Team>lambdaQuery()
                .eq(Team::getEventId, eventId)
                .eq(Team::getName, name));
        if (existing != null || !createIfAbsent) {
            return existing;
        }

        // 3. 管理员现场录队时创建赛事内队伍并生成加入标识码
        Team created = new Team();
        created.setId(IdGenerator.next("team"));
        created.setEventId(eventId);
        created.setName(name);
        created.setJoinCode(TeamCodeGenerator.next());
        created.setCreatedBy(creatorId);
        created.setCreatedAt(TimeUtil.now());
        teamMapper.insert(created);
        return created;
    }

    @Override
    @Transactional
    public void attachUser(String eventId, String userId, String teamId) {
        // 1. 无队伍时清除该赛事的成员关系
        TeamMember current = teamMemberMapper.selectOne(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getEventId, eventId)
                .eq(TeamMember::getUserId, userId).last("FOR UPDATE"));
        if (teamId == null) {
            if (current != null) {
                Team before = requireEventTeam(eventId, current.getTeamId());
                teamMemberMapper.deleteById(current.getId());
                auditService.recordTeamChange("team.member.removed", before.getId(),
                        AuditSnapshotUtil.team(eventId, userId, before), AuditSnapshotUtil.team(eventId, userId, null));
            }
            return;
        }

        // 2. 队伍与成员关系均未变化时保持原记录
        if (current != null && teamId.equals(current.getTeamId())) {
            return;
        }

        // 3. 校验目标队伍容量，并覆盖选手在本赛事的唯一队伍归属
        Team team = requireEventTeam(eventId, teamId);
        EventConfig event = requireEvent(eventId);
        assertCapacity(team, event);
        Team before = current == null ? null : requireEventTeam(eventId, current.getTeamId());
        if (current != null) {
            teamMemberMapper.deleteById(current.getId());
        }
        insertMembership(eventId, teamId, userId);

        // 4. 管理员改队记录真实的前后关系，历史成绩不会随成员关系转移
        auditService.recordTeamChange("team.member.assigned", teamId,
                AuditSnapshotUtil.team(eventId, userId, before), AuditSnapshotUtil.team(eventId, userId, team));
    }

    @Override
    public List<String> membershipTeamIds(String userId) {
        // 1. 返回账号加入过的全部赛事队伍，供历史提交访问校验使用
        return teamMemberMapper.selectList(Wrappers.<TeamMember>lambdaQuery()
                        .eq(TeamMember::getUserId, userId))
                .stream().map(TeamMember::getTeamId).distinct().toList();
    }

    @Override
    public List<TeamMember> members(String teamId) {
        // 1. 按加入时间稳定返回队伍成员
        return teamMemberMapper.selectList(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, teamId)
                .orderByAsc(TeamMember::getJoinedAt));
    }

    @Override
    public String teamName(String teamId) {
        // 1. 未组队时直接返回空，调用方自行决定展示文案
        if (teamId == null) {
            return null;
        }
        Team team = teamMapper.selectById(teamId);
        return team == null ? null : team.getName();
    }

    @Override
    public PageVO<ManagedTeamVO> adminPage(String q, PageQueryDTO query, UserPrincipal principal) {
        // 1. 在服务层校验管理员身份，避免接口授权遗漏导致成员信息泄露
        permissionAssert.assertAdmin(principal);

        // 2. 读取最近创建的当前赛事，后台队伍管理不跨赛事混合数据
        EventConfig event = eventMapper.selectOne(Wrappers.<EventConfig>lambdaQuery()
                .orderByDesc(EventConfig::getCreatedAt)
                .orderByDesc(EventConfig::getId)
                .last("LIMIT 1"));
        if (event == null) {
            throw new NotFoundException("赛事尚未配置");
        }

        // 3. 按当前赛事和可选名称关键词分页读取队伍
        IPage<Team> page = teamMapper.selectPage(new Page<>(query.page(), query.pageSize()),
                Wrappers.<Team>lambdaQuery().eq(Team::getEventId, event.getId())
                        .like(q != null && !q.isBlank(), Team::getName, q == null ? null : q.trim())
                        .orderByDesc(Team::getCreatedAt));
        List<Team> teams = page.getRecords();
        List<String> teamIds = teams.stream().map(Team::getId).toList();
        Map<String, List<TeamMemberVO>> membersByTeam = new HashMap<>();

        // 4. 一次读取本页所有成员与账号，避免逐队查询造成 N+1 请求
        if (!teamIds.isEmpty()) {
            List<TeamMember> relations = teamMemberMapper.selectList(Wrappers.<TeamMember>lambdaQuery()
                    .eq(TeamMember::getEventId, event.getId())
                    .in(TeamMember::getTeamId, teamIds)
                    .orderByAsc(TeamMember::getJoinedAt));
            List<String> userIds = relations.stream().map(TeamMember::getUserId).distinct().toList();
            Map<String, User> users = new HashMap<>();
            if (!userIds.isEmpty()) {
                userMapper.selectBatchIds(userIds).forEach(user -> users.put(user.getId(), user));
            }
            for (TeamMember relation : relations) {
                User user = users.get(relation.getUserId());
                Team team = teams.stream().filter(item -> item.getId().equals(relation.getTeamId()))
                        .findFirst().orElseThrow();
                membersByTeam.computeIfAbsent(relation.getTeamId(), ignored -> new ArrayList<>())
                        .add(new TeamMemberVO(relation.getUserId(), user == null ? null : user.getUsername(),
                                user == null ? null : user.getNickname(),
                                relation.getUserId().equals(team.getCreatedBy()), relation.getJoinedAt()));
            }
        }

        // 5. 装配队伍成员摘要与统一分页响应
        List<ManagedTeamVO> items = teams.stream().map(team -> {
            List<TeamMemberVO> members = membersByTeam.getOrDefault(team.getId(), List.of());
            return new ManagedTeamVO(team.getId(), team.getEventId(), team.getName(), team.getNote(),
                    members.size(), members, team.getCreatedAt());
        }).toList();
        return PageUtil.toPage(page, items);
    }

    @Override
    @Transactional
    public TeamBriefVO renameManagedTeam(String teamId, String name, UserPrincipal principal) {
        // 1. 在服务层校验管理员身份，避免接口授权遗漏导致越权修改
        permissionAssert.assertAdmin(principal);

        // 2. 读取当前赛事与队伍，拒绝通过后台路径跨赛事改名
        EventConfig event = eventMapper.selectOne(Wrappers.<EventConfig>lambdaQuery()
                .orderByDesc(EventConfig::getCreatedAt)
                .orderByDesc(EventConfig::getId)
                .last("LIMIT 1"));
        if (event == null) {
            throw new NotFoundException("赛事尚未配置");
        }
        Team team = teamMapper.selectOne(Wrappers.<Team>lambdaQuery()
                .eq(Team::getEventId, event.getId()).eq(Team::getId, teamId).last("FOR UPDATE"));
        if (team == null) throw new NotFoundException("赛事队伍不存在");

        // 3. 规范化队伍名称并校验同赛事唯一性
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()) {
            throw new ValidationFailedException("name", "队伍名称不能为空");
        }
        boolean duplicated = teamMapper.exists(Wrappers.<Team>lambdaQuery()
                .eq(Team::getEventId, event.getId())
                .eq(Team::getName, normalizedName)
                .ne(Team::getId, teamId));
        if (duplicated) {
            throw new ConflictException("该赛事已存在同名队伍");
        }

        // 4. 无变化时不制造变更记录，实际改名前保存旧名称
        if (normalizedName.equals(team.getName())) return new TeamBriefVO(team.getId(), team.getName());
        String before = AuditSnapshotUtil.team(event.getId(), null, team);
        team.setName(normalizedName);
        teamMapper.updateById(team);

        // 5. 同事务保存队名变更前后摘要，供最高管理员追溯
        auditService.recordTeamChange("team.renamed", teamId, before,
                AuditSnapshotUtil.team(event.getId(), null, team));
        return new TeamBriefVO(team.getId(), team.getName());
    }

    /** 读取可组队的赛事，已结束赛事不再接受队伍变更。 */
    private EventConfig requireJoinableEvent(String eventId) {
        EventConfig event = requireEvent(eventId);
        if (event.getStatus() == EventStatus.CLOSED
                || (event.getEndsAt() != null && event.getEndsAt().isBefore(TimeUtil.now()))) {
            throw new ConflictException("赛事已结束，不能创建或加入队伍");
        }
        return event;
    }

    /** 读取赛事实体。 */
    private EventConfig requireEvent(String eventId) {
        EventConfig event = eventId == null ? null : eventMapper.selectById(eventId);
        if (event == null) {
            throw new NotFoundException("赛事不存在");
        }
        return event;
    }

    /** 校验选手在赛事内没有既有队伍。 */
    private void requireNoMembership(String eventId, String userId) {
        boolean exists = teamMemberMapper.exists(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getEventId, eventId)
                .eq(TeamMember::getUserId, userId));
        if (exists) {
            throw new ConflictException("当前赛事已加入队伍，不能重复创建或加入");
        }
    }

    /** 校验队伍成员数未超过赛事上限。 */
    private void assertCapacity(Team team, EventConfig event) {
        // 1. 锁定队伍行并使用当前读统计成员，避免不同邀请码写法或后台并发分配突破人数上限
        teamMapper.selectOne(Wrappers.<Team>lambdaQuery()
                .eq(Team::getId, team.getId()).last("FOR UPDATE"));
        long memberCount = teamMemberMapper.selectList(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, team.getId()).last("FOR UPDATE")).size();
        int maxMembers = event.getMaxTeamSize() == null ? 3 : Math.min(3, event.getMaxTeamSize());
        if (memberCount >= maxMembers) {
            throw new ConflictException("队伍人数已达到当前赛事上限");
        }
    }

    /** 写入赛事成员关系。 */
    private void insertMembership(String eventId, String teamId, String userId) {
        TeamMember member = new TeamMember();
        member.setId(IdGenerator.next("tm"));
        member.setEventId(eventId);
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setJoinedAt(TimeUtil.now());
        teamMemberMapper.insert(member);
    }

    /** 组装选手端队伍视图。 */
    private TeamMembershipVO toVO(Team team, EventConfig event, String viewerId) {
        long memberCount = teamMemberMapper.selectCount(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, team.getId()));
        return new TeamMembershipVO(team.getId(), team.getEventId(), team.getName(), team.getJoinCode(),
                viewerId.equals(team.getCreatedBy()), (int) memberCount,
                event.getMaxTeamSize() == null ? 3 : Math.min(3, event.getMaxTeamSize()));
    }
}

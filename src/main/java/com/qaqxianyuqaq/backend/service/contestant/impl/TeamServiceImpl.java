package com.qaqxianyuqaq.backend.service.contestant.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.aspect.DistributedLock;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamJoinDTO;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.TeamMember;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMembershipVO;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.repository.event.EventMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMemberMapper;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TeamCodeGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** 赛事队伍服务实现。 */
@Service
public class TeamServiceImpl implements TeamService {

    private final TeamMapper teamMapper;

    private final TeamMemberMapper teamMemberMapper;

    private final EventMapper eventMapper;

    private final PermissionAssert permissionAssert;

    public TeamServiceImpl(TeamMapper teamMapper, TeamMemberMapper teamMemberMapper,
                           EventMapper eventMapper, PermissionAssert permissionAssert) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.eventMapper = eventMapper;
        this.permissionAssert = permissionAssert;
    }

    @Override
    @Transactional
    @DistributedLock(key = "'team-create:' + #eventId + ':' + #principal.userId()")
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
        return toVO(team, event, principal.userId());
    }

    @Override
    @Transactional
    @DistributedLock(key = "'team-join:' + #eventId + ':' + #request.code()")
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
        return toVO(team, event, principal.userId());
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
                .eq(TeamMember::getUserId, userId));
        if (teamId == null) {
            if (current != null) {
                teamMemberMapper.deleteById(current.getId());
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
        if (current != null) {
            teamMemberMapper.deleteById(current.getId());
        }
        insertMembership(eventId, teamId, userId);
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
        long memberCount = teamMemberMapper.selectCount(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, team.getId()));
        int maxMembers = event.getMaxTeamSize() == null ? 1 : event.getMaxTeamSize();
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
                event.getMaxTeamSize() == null ? 1 : event.getMaxTeamSize());
    }
}

package com.qaqxianyuqaq.backend.service.contestant.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamJoinDTO;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.TeamMember;
import com.qaqxianyuqaq.backend.domain.enums.EventStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.repository.event.EventMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.repository.user.TeamMemberMapper;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 赛事队伍服务单元测试：覆盖创建队伍与人数上限。 */
@ExtendWith(MockitoExtension.class)
class TeamServiceImplTest {

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private TeamMemberMapper teamMemberMapper;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private PermissionAssert permissionAssert;

    @InjectMocks
    private TeamServiceImpl teamService;

    private UserPrincipal principal;

    private EventConfig event;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), TeamMember.class);
        principal = new UserPrincipal("usr_captain", "captain", UserRole.CONTESTANT, null, 0);
        event = new EventConfig();
        event.setId("evt_1");
        event.setStatus(EventStatus.OPEN);
        event.setMaxTeamSize(2);
        event.setEndsAt(TimeUtil.now().plusDays(1));
    }

    @Test
    @DisplayName("创建队伍时生成唯一标识码并将创建者自动入队")
    void shouldCreateTeamAndAttachCaptain() {
        when(eventMapper.selectById("evt_1")).thenReturn(event);
        when(teamMemberMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(teamMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        var result = teamService.createTeam("evt_1", new TeamCreateDTO("Alpha"), principal);

        assertThat(result.id()).startsWith("team_");
        assertThat(result.joinCode()).startsWith("TQ-");
        assertThat(result.captain()).isTrue();
        assertThat(result.memberCount()).isEqualTo(1);
        verify(teamMapper).insert(any(Team.class));
        verify(teamMemberMapper).insert(any(TeamMember.class));
    }

    @Test
    @DisplayName("队伍人数达到赛事上限时拒绝成员加入")
    void shouldRejectJoinWhenTeamIsFull() {
        Team team = new Team();
        team.setId("team_1");
        team.setEventId("evt_1");
        team.setJoinCode("TQ-ABCDEFGHIJ");
        when(eventMapper.selectById("evt_1")).thenReturn(event);
        when(teamMemberMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(teamMapper.selectOne(any(Wrapper.class))).thenReturn(team);
        when(teamMemberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(new TeamMember(), new TeamMember()));

        assertThatThrownBy(() -> teamService.joinTeam("evt_1",
                new TeamJoinDTO("TQ-ABCDEFGHIJ"), principal))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("上限");
        verify(teamMemberMapper, never()).insert(any(TeamMember.class));
    }
    @Test
    @DisplayName("旧赛事配置超过三人时仍拒绝第四名成员")
    void shouldCapLegacyTeamAtThree() {
        event.setMaxTeamSize(100);
        Team team = new Team();
        team.setId("team_1");
        team.setEventId("evt_1");
        when(eventMapper.selectById("evt_1")).thenReturn(event);
        when(teamMapper.selectOne(any(Wrapper.class))).thenReturn(team);
        when(teamMemberMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(new TeamMember(), new TeamMember(), new TeamMember()));

        assertThatThrownBy(() -> teamService.joinTeam("evt_1", new TeamJoinDTO("tq-mixed"), principal))
                .isInstanceOf(ConflictException.class);
        verify(teamMemberMapper, never()).insert(any(TeamMember.class));
    }

    @Test
    @DisplayName("退出仅删除本人的赛事成员关系并允许重复操作")
    void shouldLeaveOnlyOwnMembershipIdempotently() {
        when(eventMapper.selectById("evt_1")).thenReturn(event);
        teamService.leaveTeam("evt_1", principal);
        teamService.leaveTeam("evt_1", principal);
        var query = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(teamMemberMapper, org.mockito.Mockito.times(2)).delete(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("event_id", "user_id");
        var params = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) query.getValue())
                .getParamNameValuePairs();
        assertThat(params.values()).contains("evt_1", "usr_captain");
        org.mockito.Mockito.verifyNoInteractions(teamMapper);
    }

    @Test
    @DisplayName("管理员分配也不能突破三人硬上限")
    void shouldRejectAdminAssignmentToFullTeam() {
        event.setMaxTeamSize(4);
        Team team = new Team();
        team.setId("team_1");
        team.setEventId("evt_1");
        when(eventMapper.selectById("evt_1")).thenReturn(event);
        when(teamMapper.selectById("team_1")).thenReturn(team);
        when(teamMapper.selectOne(any(Wrapper.class))).thenReturn(team);
        when(teamMemberMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(new TeamMember(), new TeamMember(), new TeamMember()));
        assertThatThrownBy(() -> teamService.attachUser("evt_1", "usr_new", "team_1"))
                .isInstanceOf(ConflictException.class);
        verify(teamMemberMapper, never()).insert(any(TeamMember.class));
    }
}

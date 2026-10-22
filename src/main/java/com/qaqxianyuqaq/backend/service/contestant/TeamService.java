package com.qaqxianyuqaq.backend.service.contestant;

import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamJoinDTO;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.TeamMember;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMembershipVO;
import com.qaqxianyuqaq.backend.security.UserPrincipal;

import java.util.List;

/** 赛事队伍服务：创建、邀请码加入、成员校验与后台队伍维护。 */
public interface TeamService {

    /** 选手在当前赛事创建队伍，创建者自动成为队长。 */
    TeamMembershipVO createTeam(String eventId, TeamCreateDTO request, UserPrincipal principal);

    /** 选手使用队伍唯一标识码加入赛事队伍。 */
    TeamMembershipVO joinTeam(String eventId, TeamJoinDTO request, UserPrincipal principal);

    /** 查询选手在指定赛事中的队伍，未加入时返回 null。 */
    TeamMembershipVO myTeam(String eventId, UserPrincipal principal);

    /** 查询选手在指定赛事中的队伍实体，未加入时返回 null。 */
    Team findMembership(String eventId, String userId);

    /** 要求选手已加入指定赛事队伍。 */
    Team requireMembership(String eventId, String userId);

    /** 读取赛事内队伍并校验赛事归属。 */
    Team requireEventTeam(String eventId, String teamId);

    /** 管理员按队伍编号或名称解析队伍，必要时创建。 */
    Team resolveManagedTeam(String eventId, String teamId, String teamName,
                            boolean createIfAbsent, String creatorId);

    /** 管理员覆盖选手在指定赛事中的队伍关系。 */
    void attachUser(String eventId, String userId, String teamId);

    /** 查询选手加入过的赛事队伍标识，用于提交记录归属校验。 */
    List<String> membershipTeamIds(String userId);

    /** 查询队伍全部成员。 */
    List<TeamMember> members(String teamId);

    /** 读取队伍名称，队伍不存在或未组队时返回 null。 */
    String teamName(String teamId);
}

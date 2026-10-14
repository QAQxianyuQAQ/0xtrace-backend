package com.qaqxianyuqaq.backend.service.contestant.impl;

import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.repository.user.TeamMapper;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import org.springframework.stereotype.Service;

/** 队伍查询服务实现。 */
@Service
public class TeamServiceImpl implements TeamService {

    private final TeamMapper teamMapper;

    public TeamServiceImpl(TeamMapper teamMapper) {
        this.teamMapper = teamMapper;
    }

    @Override
    public String teamName(String teamId) {
        // 未组队时直接返回 null，调用方自行决定展示文案
        if (teamId == null) {
            return null;
        }
        Team team = teamMapper.selectById(teamId);
        return team == null ? null : team.getName();
    }
}

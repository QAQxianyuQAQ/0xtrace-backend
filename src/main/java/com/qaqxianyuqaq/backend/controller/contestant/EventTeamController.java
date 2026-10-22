package com.qaqxianyuqaq.backend.controller.contestant;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamCreateDTO;
import com.qaqxianyuqaq.backend.domain.dto.contestant.TeamJoinDTO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamMembershipVO;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 选手端赛事队伍接口：队伍关系只在路径指定赛事内生效。 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/teams")
public class EventTeamController {

    private final TeamService teamService;

    public EventTeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    /** 创建赛事队伍并返回只对该赛事有效的唯一标识码。 */
    @PostMapping
    @RateLimit(scope = "team-write")
    @AuditAction(value = "team.created", entityType = "team")
    public ResponseEntity<TeamMembershipVO> create(
            @PathVariable String eventId,
            @Valid @RequestBody TeamCreateDTO request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.createTeam(eventId, request, principal));
    }

    /** 使用队伍唯一标识码加入赛事队伍。 */
    @PostMapping("/join")
    @RateLimit(scope = "team-write")
    @AuditAction(value = "team.joined", entityType = "team")
    public TeamMembershipVO join(
            @PathVariable String eventId,
            @Valid @RequestBody TeamJoinDTO request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return teamService.joinTeam(eventId, request, principal);
    }

    /** 查看当前选手在该赛事的队伍。 */
    @GetMapping("/me")
    public TeamMembershipVO myTeam(
            @PathVariable String eventId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return teamService.myTeam(eventId, principal);
    }
}

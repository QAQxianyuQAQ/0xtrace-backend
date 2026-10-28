package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.ChallengeContainerInstance;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ChallengeRuntimeVO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.ConnectionTicketVO;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.security.ConnectionTicketService;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeContainerService;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 选手队伍题目容器与 WebSocket 连接票据接口。 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/challenges/{code}/runtime")
public class ChallengeRuntimeController {

    private final ChallengeService challengeService;

    private final ChallengeContainerService containerService;

    private final ConnectionTicketService ticketService;

    private final TeamService teamService;

    private final PermissionAssert permissionAssert;

    public ChallengeRuntimeController(ChallengeService challengeService,
                                      ChallengeContainerService containerService,
                                      ConnectionTicketService ticketService, TeamService teamService,
                                      PermissionAssert permissionAssert) {
        this.challengeService = challengeService;
        this.containerService = containerService;
        this.ticketService = ticketService;
        this.teamService = teamService;
        this.permissionAssert = permissionAssert;
    }

    /** 启动或复用当前队伍题目容器。 */
    @PostMapping("/instances")
    @RateLimit(scope = "container-start")
    public ResponseEntity<ChallengeRuntimeVO> start(@PathVariable String eventId, @PathVariable String code,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        permissionAssert.assertContestant(principal);
        Challenge challenge = challenge(eventId, code);
        Team team = teamService.requireMembership(eventId, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(containerService.ensureStarted(challenge, team));
    }

    /** 查询当前队伍容器状态。 */
    @GetMapping("/instances/me")
    public ChallengeRuntimeVO status(@PathVariable String eventId, @PathVariable String code,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        permissionAssert.assertContestant(principal);
        Challenge challenge = challenge(eventId, code);
        Team team = teamService.requireMembership(eventId, principal.userId());
        return containerService.status(challenge, team);
    }

    /** 停止并删除当前队伍容器。 */
    @DeleteMapping("/instances/me")
    @RateLimit(scope = "container-stop")
    public ResponseEntity<Void> stop(@PathVariable String eventId, @PathVariable String code,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        permissionAssert.assertContestant(principal);
        Challenge challenge = challenge(eventId, code);
        Team team = teamService.requireMembership(eventId, principal.userId());
        containerService.stop(challenge, team);
        return ResponseEntity.noContent().build();
    }

    /** 剩余时间不超过 30 分钟时延长本队容器一小时。 */
    @PostMapping("/instances/me/extend")
    @RateLimit(scope = "container-extend")
    public ChallengeRuntimeVO extend(@PathVariable String eventId, @PathVariable String code,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        permissionAssert.assertContestant(principal);
        Challenge challenge = challenge(eventId, code);
        Team team = teamService.requireMembership(eventId, principal.userId());
        return containerService.extend(challenge, team);
    }

    /** 为本队已就绪容器签发一次性 WebSocket 连接票据。 */
    @PostMapping("/connection-ticket")
    @RateLimit(scope = "container-ticket")
    public ConnectionTicketVO ticket(@PathVariable String eventId, @PathVariable String code,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        permissionAssert.assertContestant(principal);
        Challenge challenge = challenge(eventId, code);
        Team team = teamService.requireMembership(eventId, principal.userId());
        ChallengeContainerInstance instance = containerService.requireReady(challenge, team);
        return ticketService.issue(principal, challenge, team, instance);
    }

    /** 校验题目存在且确实独占指定赛事。 */
    private Challenge challenge(String eventId, String code) {
        Challenge challenge = challengeService.requireByCode(code);
        if (!eventId.equals(challenge.getEventId())) {
            throw new NotFoundException("题目不存在或不属于该赛事");
        }
        return challenge;
    }
}

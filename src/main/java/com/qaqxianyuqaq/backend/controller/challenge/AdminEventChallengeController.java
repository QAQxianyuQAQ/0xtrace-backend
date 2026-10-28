package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.domain.dto.challenge.ChallengeCreateDTO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.AdminChallengeVO;
import com.qaqxianyuqaq.backend.security.PermissionAssert;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.ChallengeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 赛事内题目创建接口：题目明确归当前路径赛事独占。 */
@RestController
@RequestMapping("/api/v1/admin/events/{eventId}/challenges")
public class AdminEventChallengeController {

    private final ChallengeService challengeService;

    private final PermissionAssert permissionAssert;

    public AdminEventChallengeController(ChallengeService challengeService, PermissionAssert permissionAssert) {
        this.challengeService = challengeService;
        this.permissionAssert = permissionAssert;
    }

    /** 在比赛路径指定赛事中创建题目。 */
    @PostMapping
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "challenge.created", entityType = "challenge")
    public ResponseEntity<AdminChallengeVO> create(@PathVariable String eventId,
                                                   @Valid @RequestBody ChallengeCreateDTO request,
                                                   @AuthenticationPrincipal UserPrincipal principal) {
        permissionAssert.assertAdmin(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(challengeService.create(eventId, request));
    }
}

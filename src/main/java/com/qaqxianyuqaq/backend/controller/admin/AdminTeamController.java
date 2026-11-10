package com.qaqxianyuqaq.backend.controller.admin;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.domain.dto.admin.ManagedTeamRenameDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.admin.ManagedTeamVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.contestant.TeamBriefVO;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台赛事队伍查询与名称维护接口。 */
@RestController
@RequestMapping("/api/v1/admin/teams")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTeamController {

    private final TeamService teamService;

    public AdminTeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    /** 分页读取当前赛事队伍及成员摘要。 */
    @GetMapping
    public PageVO<ManagedTeamVO> list(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer pageSize,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return teamService.adminPage(q, PageUtil.normalize(page, pageSize), principal);
    }

    /** 修改当前赛事指定队伍名称。 */
    @PatchMapping("/{teamId}")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "team.renamed", entityType = "team", entityIdParam = "teamId")
    public TeamBriefVO rename(@PathVariable String teamId,
                              @Valid @RequestBody ManagedTeamRenameDTO request,
                              @AuthenticationPrincipal UserPrincipal principal) {
        return teamService.renameManagedTeam(teamId, request.name(), principal);
    }
}

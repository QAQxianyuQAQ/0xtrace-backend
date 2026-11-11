package com.qaqxianyuqaq.backend.controller.admin;

import com.qaqxianyuqaq.backend.aspect.AuditAction;
import com.qaqxianyuqaq.backend.aspect.RateLimit;
import com.qaqxianyuqaq.backend.domain.dto.admin.AdminRoleUpdateDTO;
import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.admin.ManagedAccountVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.admin.AdminAccountService;
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

/** 最高管理员对普通管理员角色的管理接口。 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    public AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    /** 分页读取普通管理员账号，不包含最高管理员。 */
    @GetMapping("/administrators")
    public PageVO<ManagedAccountVO> administrators(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer pageSize,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return adminAccountService.administrators(q, PageUtil.normalize(page, pageSize), principal);
    }

    /** 授予或撤销普通管理员权限。 */
    @PatchMapping("/{userId}/role")
    @RateLimit(scope = "admin-write")
    @AuditAction(value = "account.role.updated", entityType = "account", entityIdParam = "userId")
    public ManagedAccountVO updateRole(@PathVariable String userId,
                                       @Valid @RequestBody AdminRoleUpdateDTO request,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return adminAccountService.updateRole(userId, request, principal);
    }
}

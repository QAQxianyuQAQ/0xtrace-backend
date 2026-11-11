package com.qaqxianyuqaq.backend.controller.admin;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.admin.AuditLogQueryDTO;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ModelAttribute;
import com.qaqxianyuqaq.backend.domain.vo.admin.AuditLogVO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;

import com.qaqxianyuqaq.backend.service.audit.AuditService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台审计日志接口。 */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAuditLogController {

    private final AuditService auditService;

    public AdminAuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 分页检索后台操作审计记录。 */
    @GetMapping
    public PageVO<AuditLogVO> list(@Valid @ModelAttribute AuditLogQueryDTO filter,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer pageSize,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return auditService.page(filter, query, principal);
    }
}

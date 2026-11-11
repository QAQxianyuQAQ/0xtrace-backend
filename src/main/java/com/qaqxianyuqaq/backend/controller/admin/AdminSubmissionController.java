package com.qaqxianyuqaq.backend.controller.admin;

import com.qaqxianyuqaq.backend.domain.dto.admin.AdminSubmissionQueryDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.ManagedSubmissionVO;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.submission.SubmissionService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 管理端全量判题记录查询，只读且不修改任何计分规则。 */
@RestController
@RequestMapping("/api/v1/admin/submissions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSubmissionController {
    private final SubmissionService submissionService;
    public AdminSubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping
    public PageVO<ManagedSubmissionVO> list(@Valid @ModelAttribute AdminSubmissionQueryDTO filter,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize,
            @AuthenticationPrincipal UserPrincipal principal) {
        return submissionService.adminPage(filter, PageUtil.normalize(page, pageSize), principal);
    }
}

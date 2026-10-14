package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.domain.dto.submission.SubmitAnswerDTO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.submission.SubmissionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 答案提交接口。 */
@RestController
@RequestMapping("/api/v1/challenges")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /** 提交文本/Flag 答案，判题与计分由服务端权威完成。 */
    @PostMapping("/{code}/submissions")
    public SubmissionResultVO submit(@PathVariable String code,
                                                    @Valid @RequestBody SubmitAnswerDTO request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return submissionService.submit(code, request, principal);
    }
}

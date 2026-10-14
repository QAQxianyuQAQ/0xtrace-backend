package com.qaqxianyuqaq.backend.controller.me;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.profile.UpdateProfileDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.profile.ProfileVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.MyProgressVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.profile.ProfileService;
import com.qaqxianyuqaq.backend.service.submission.SubmissionService;
import com.qaqxianyuqaq.backend.util.PageUtil;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 个人资料、进度与提交记录接口。 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final ProfileService profileService;

    private final SubmissionService submissionService;

    public MeController(ProfileService profileService, SubmissionService submissionService) {
        this.profileService = profileService;
        this.submissionService = submissionService;
    }

    /** 更新昵称与头像地址。 */
    @PatchMapping("/profile")
    public ProfileVO updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody UpdateProfileDTO request) {
        return profileService.update(principal, request);
    }

    /** 个人/队伍解题进度与累计分数。 */
    @GetMapping("/progress")
    public MyProgressVO progress(@AuthenticationPrincipal UserPrincipal principal) {
        return submissionService.myProgress(principal);
    }

    /** 分页查询本人或本队提交记录。 */
    @GetMapping("/submissions")
    public PageVO<SubmissionVO> submissions(@AuthenticationPrincipal UserPrincipal principal,
                                                           @RequestParam(required = false) String challengeCode,
                                                           @RequestParam(required = false) String result,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer pageSize) {
        PageQueryDTO query = PageUtil.normalize(page, pageSize);
        return submissionService.mySubmissions(principal, challengeCode, result, query);
    }

    /** 查询单条提交结果。 */
    @GetMapping("/submissions/{submissionId}")
    public SubmissionVO submission(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable String submissionId) {
        return submissionService.getSubmission(submissionId, principal);
    }
}

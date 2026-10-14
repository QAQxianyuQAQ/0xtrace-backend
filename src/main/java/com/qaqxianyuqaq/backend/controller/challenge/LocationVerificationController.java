package com.qaqxianyuqaq.backend.controller.challenge;

import com.qaqxianyuqaq.backend.domain.dto.submission.LocationVerificationDTO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.challenge.LocationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 现场位置二维码核验接口。 */
@RestController
@RequestMapping("/api/v1/location-verifications")
public class LocationVerificationController {

    private final LocationService locationService;

    public LocationVerificationController(LocationService locationService) {
        this.locationService = locationService;
    }

    /** 提交现场二维码内容，由服务端核验令牌并记分。 */
    @PostMapping
    public SubmissionResultVO verify(
            @Valid @RequestBody LocationVerificationDTO request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return locationService.verify(request, principal);
    }
}

package com.qaqxianyuqaq.backend.service.challenge;

import com.qaqxianyuqaq.backend.domain.dto.challenge.LocationQrRequestDTO;
import com.qaqxianyuqaq.backend.domain.dto.submission.LocationVerificationDTO;
import com.qaqxianyuqaq.backend.domain.vo.challenge.LocationQrVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 现场位置二维码服务：签发、轮换、撤销与核验。 */
public interface LocationService {

    /** 为已发布图寻题目签发或轮换位置凭证。 */
    LocationQrVO issueQr(String code, LocationQrRequestDTO request);

    /** 撤销题目当前位置凭证。 */
    void revokeQr(String code);

    /** 核验选手提交的二维码内容并记分。 */
    SubmissionResultVO verify(LocationVerificationDTO request, UserPrincipal principal);
}

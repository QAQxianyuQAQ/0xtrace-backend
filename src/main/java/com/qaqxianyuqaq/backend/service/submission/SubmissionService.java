package com.qaqxianyuqaq.backend.service.submission;

import com.qaqxianyuqaq.backend.domain.dto.common.PageQueryDTO;
import com.qaqxianyuqaq.backend.domain.dto.submission.SubmitAnswerDTO;
import com.qaqxianyuqaq.backend.domain.vo.common.PageVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.MyProgressVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;
import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionVO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 答案提交与提交记录服务。 */
public interface SubmissionService {

    /** 提交文本/Flag 答案，服务端权威判题并原子记分。 */
    SubmissionResultVO submit(String code, SubmitAnswerDTO request,
                                             UserPrincipal principal);

    /** 查询本人提交结果。 */
    SubmissionVO getSubmission(String submissionId, UserPrincipal principal);

    /** 分页查询本人或本队提交记录。 */
    PageVO<SubmissionVO> mySubmissions(UserPrincipal principal, String challengeCode, String result,
                                                      PageQueryDTO query);

    /** 个人/队伍解题进度与累计分数。 */
    MyProgressVO myProgress(UserPrincipal principal);
}

package com.qaqxianyuqaq.backend.service.submission;

import com.qaqxianyuqaq.backend.domain.vo.submission.SubmissionResultVO;

import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.enums.SolveSource;
import com.qaqxianyuqaq.backend.security.UserPrincipal;

/** 计分引擎：首解判定、额外加分与重复提交幂等在此统一处理。 */
public interface ScoringService {

    /**
     * 判定并记分。
     *
     * @param principal  当前选手
     * @param challenge  目标题目
     * @param source     解题来源（文本答案或位置核验）
     * @param answerText 提交内容，错误时不落明文
     * @param requestId  客户端请求 ID
     * @param correct    是否判定通过
     * @return 与 8.3 节一致的判题结果
     */
    SubmissionResultVO award(UserPrincipal principal, Challenge challenge, SolveSource source,
                                            String answerText, String requestId, boolean correct);

    /** 组装选手可见的题目状态（unsolved/solved），供题目接口复用。 */
    String myStatus(UserPrincipal viewer, Challenge challenge);
}

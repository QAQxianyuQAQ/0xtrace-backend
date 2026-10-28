package com.qaqxianyuqaq.backend.aspect;

import com.qaqxianyuqaq.backend.exception.FlagOwnerException;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Flag 归属异常日志切面：统一记录疑似串通行为。 */
@Aspect
@Component
@Order(20)
public class FlagOwnerLogAspect {

    private static final Logger log = LoggerFactory.getLogger(FlagOwnerLogAspect.class);

    /** 捕获非本队 Flag 异常并写入告警日志。 */
    @AfterThrowing(
            pointcut = "execution(* com.qaqxianyuqaq.backend.service.submission.SubmissionService.submit(..))",
            throwing = "exception")
    public void logForeignFlag(FlagOwnerException exception) {
        log.warn("队伍{}成员{}疑似作弊", exception.teamName(), exception.username());
    }
}

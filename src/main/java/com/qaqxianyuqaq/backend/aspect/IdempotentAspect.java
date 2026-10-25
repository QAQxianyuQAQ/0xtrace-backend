package com.qaqxianyuqaq.backend.aspect;
import com.qaqxianyuqaq.backend.security.SecurityUtils;
import com.qaqxianyuqaq.backend.service.cache.IdempotencyService;
import com.qaqxianyuqaq.backend.domain.dto.submission.SubmitAnswerDTO;
import com.qaqxianyuqaq.backend.domain.dto.submission.LocationVerificationDTO;
import com.qaqxianyuqaq.backend.exception.ValidationFailedException;
import com.qaqxianyuqaq.backend.util.DigestUtil;
import com.qaqxianyuqaq.backend.util.JsonUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import java.util.*;
/** 数据库唯一键认领、业务和响应快照同事务提交；进程退出自动回滚占位。 */
@Aspect @Component @Order(20)
public class IdempotentAspect {
    private final IdempotencyService service;
    private final TransactionTemplate transaction;
    public IdempotentAspect(IdempotencyService service, PlatformTransactionManager manager) {
        this.service=service;
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }
    @Around("@annotation(idempotent)")
    public Object apply(ProceedingJoinPoint point, Idempotent idempotent) throws Throwable {
        String header=null, bodyId=null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
            header=attributes.getRequest().getHeader("Idempotency-Key");
        for (Object arg : point.getArgs()) {
            if (arg instanceof SubmitAnswerDTO answer) bodyId=answer.requestId();
            if (arg instanceof LocationVerificationDTO location) bodyId=location.requestId();
        }
        Map<String,String> keys=new LinkedHashMap<>();
        if (header!=null && !header.isBlank()) keys.put(idempotent.scope(),header);
        if (bodyId!=null && !bodyId.isBlank()) keys.put("request",bodyId);
        if (keys.isEmpty()) return point.proceed();
        if (keys.values().stream().anyMatch(key -> key.length()>128 || key.chars().anyMatch(Character::isISOControl)))
            throw new ValidationFailedException("Idempotency-Key", "幂等键最多 128 位且不能包含控制字符");
        var principal=SecurityUtils.currentPrincipalOrNull();
        String userId=principal==null?"":principal.userId();
        // 账号由唯一键隔离；密码散列、会话版本和队伍缓存不属于请求内容。
        Object[] arguments=Arrays.stream(point.getArgs())
                .filter(arg -> !(arg instanceof com.qaqxianyuqaq.backend.security.UserPrincipal)).toArray();
        String content=JsonUtil.toJson(arguments);
        if(content==null) throw new IllegalStateException("幂等请求无法序列化");
        String hash=DigestUtil.sha256Hex(idempotent.scope()+content);
        Class<?> type=((MethodSignature)point.getSignature()).getReturnType();
        return transaction.execute(status -> {
            Optional<String> cached=Optional.empty();
            for (var key: keys.entrySet()) {
                Optional<String> candidate=service.findResponse(key.getKey(),key.getValue(),userId,hash);
                if (candidate.isPresent()) cached=candidate;
            }
            Object result;
            if (cached.isPresent()) {
                result=JsonUtil.fromJson(cached.get(),type);
                if (result==null && type!=void.class) throw new IllegalStateException("幂等响应无法读取");
            } else {
                try { result=point.proceed(); }
                catch (RuntimeException|Error failure) { throw failure; }
                catch (Throwable failure) { throw new IllegalStateException("幂等业务执行失败",failure); }
            }
            String snapshot=JsonUtil.toJson(result);
            if (snapshot==null && type!=void.class) throw new IllegalStateException("幂等响应无法序列化");
            for (var key:keys.entrySet()) service.saveResponse(key.getKey(),key.getValue(),userId,hash,snapshot);
            return result;
        });
    }
}

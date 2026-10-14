package com.qaqxianyuqaq.backend.aspect;

import com.qaqxianyuqaq.backend.exception.ConflictException;
import com.qaqxianyuqaq.backend.service.cache.CacheService;
import com.qaqxianyuqaq.backend.util.CacheKeys;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 分布式锁切面。
 *
 * <p>并发写路径（解题计分）通过 Redis 锁串行化，避免同一账号重复得分；锁超时自动释放。</p>
 */
@Aspect
@Component
@Order(30)
public class DistributedLockAspect {

    /** 未获取到锁时的重试间隔（毫秒）。 */
    private static final long RETRY_INTERVAL_MILLIS = 50;

    private final CacheService cacheService;

    private final ExpressionParser parser = new SpelExpressionParser();

    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public DistributedLockAspect(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** 环绕通知：加锁执行，结束后释放。 */
    @Around("@annotation(lock)")
    public Object lockAndProceed(ProceedingJoinPoint joinPoint, DistributedLock lock) throws Throwable {
        String lockKey = CacheKeys.lock(evaluateKey(joinPoint, lock.key()));
        String lockValue = IdGenerator.next("lock");
        long deadline = System.currentTimeMillis() + lock.waitMillis();

        // 1. 自旋获取锁，超过等待时间按冲突处理，由客户端稍后重试
        boolean acquired = cacheService.setIfAbsent(lockKey, lockValue, Duration.ofMillis(lock.leaseMillis()));
        while (!acquired && System.currentTimeMillis() < deadline) {
            Thread.sleep(RETRY_INTERVAL_MILLIS);
            acquired = cacheService.setIfAbsent(lockKey, lockValue, Duration.ofMillis(lock.leaseMillis()));
        }
        if (!acquired) {
            throw new ConflictException("该题目正在处理其他提交，请稍后重试");
        }

        // 2. 持有锁执行业务逻辑，异常同样释放锁
        try {
            return joinPoint.proceed();
        } finally {
            // 仅在当前持有者仍是锁主人时释放，避免删除他人重新获取的锁
            boolean owned = cacheService.get(lockKey).filter(lockValue::equals).isPresent();
            if (owned) {
                cacheService.delete(lockKey);
            }
        }
    }

    /** 解析 SpEL 锁键表达式。 */
    private String evaluateKey(ProceedingJoinPoint joinPoint, String expression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        EvaluationContext context = new StandardEvaluationContext();
        Object[] args = joinPoint.getArgs();
        if (parameterNames != null) {
            for (int index = 0; index < parameterNames.length; index++) {
                context.setVariable(parameterNames[index], args[index]);
            }
        }
        Expression parsed = parser.parseExpression(expression);
        Object value = parsed.getValue(context);
        return value == null ? method.getName() : String.valueOf(value);
    }
}

package com.qaqxianyuqaq.backend.aspect;

import com.qaqxianyuqaq.backend.domain.dto.audit.AuditCommandDTO;

import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.security.SecurityUtils;
import com.qaqxianyuqaq.backend.service.audit.AuditService;
import com.qaqxianyuqaq.backend.util.JsonUtil;
import com.qaqxianyuqaq.backend.util.RequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

/**
 * 审计切面。
 *
 * <p>管理端写操作统一在此落地审计记录，业务实现不重复书写审计代码。</p>
 */
@Aspect
@Component
@Order(50)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    /** 审计对象 ID 最大长度，与 audit_logs.entity_id 列宽保持一致。 */
    private static final int MAX_ENTITY_ID_LENGTH = 40;

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 环绕通知：方法成功返回后记录审计日志。 */
    @Around("@annotation(auditAction)")
    public Object recordAudit(ProceedingJoinPoint joinPoint, AuditAction auditAction) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            UserPrincipal principal = SecurityUtils.currentPrincipalOrNull();
            HttpServletRequest request = currentRequest();
            auditService.record(new AuditCommandDTO(
                    principal == null ? null : principal.userId(),
                    principal == null ? null : principal.getUsername(),
                    auditAction.value(),
                    auditAction.entityType(),
                    resolveEntityId(joinPoint, auditAction, result),
                    null,
                    JsonUtil.toJson(result),
                    RequestUtil.currentRequestId(),
                    RequestUtil.clientIp(request)));
        } catch (RuntimeException exception) {
            // 审计失败不应影响已完成的业务操作，仅记录服务端日志
            log.warn("审计写入失败: action={}", auditAction.value(), exception);
        }
        return result;
    }

    /** 解析审计对象 ID：优先使用注解指定的字符串参数，其次尝试返回值中的 code/id。 */
    private String resolveEntityId(ProceedingJoinPoint joinPoint, AuditAction auditAction, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = signature.getParameterNames();
        if (auditAction.entityIdParam() != null && !auditAction.entityIdParam().isBlank()
                && parameterNames != null) {
            for (int index = 0; index < parameterNames.length; index++) {
                if (auditAction.entityIdParam().equals(parameterNames[index])) {
                    Object value = joinPoint.getArgs()[index];
                    // 仅接受字符串型参数（如题目编号、账号 ID），避免把整个请求体写入对象 ID
                    if (value instanceof String identifier) {
                        return truncate(identifier);
                    }
                }
            }
        }
        if (result == null) {
            return null;
        }
        if (result instanceof String text) {
            return truncate(text);
        }
        if (result.getClass().isRecord()) {
            for (RecordComponent component : result.getClass().getRecordComponents()) {
                if ("code".equals(component.getName()) || "id".equals(component.getName())) {
                    try {
                        Object value = component.getAccessor().invoke(result);
                        if (value instanceof String identifier) {
                            return truncate(identifier);
                        }
                    } catch (ReflectiveOperationException exception) {
                        log.debug("读取审计对象 ID 失败", exception);
                    }
                }
            }
        }
        return null;
    }

    /** 截断超长对象 ID，避免审计写入因列宽溢出失败。 */
    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ENTITY_ID_LENGTH ? value : value.substring(0, MAX_ENTITY_ID_LENGTH);
    }

    /** 获取当前 HTTP 请求，非 Web 上下文返回 null。 */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}

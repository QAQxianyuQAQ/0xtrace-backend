package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 安全上下文辅助工具，避免业务代码散落强转逻辑。 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 获取当前登录主体，未登录时抛出 401 业务异常。 */
    public static UserPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        return principal;
    }

    /** 获取当前登录主体，未登录时返回 null。 */
    public static UserPrincipal currentPrincipalOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal;
    }
}

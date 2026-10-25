package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.ForbiddenException;
import com.qaqxianyuqaq.backend.exception.UnauthorizedException;
import org.springframework.stereotype.Component;

/**
 * 权限断言组件。
 *
 * <p>横切的权限判定集中在此处，业务实现不再重复书写角色比较逻辑。</p>
 */
@Component
public class PermissionAssert {

    /** 断言当前主体为管理员。 */
    public void assertAdmin(UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        if (principal.role() != UserRole.ADMIN) {
            throw new ForbiddenException("该操作仅限管理员");
        }
    }

    /** 断言当前主体为已登录选手（管理员亦可作答，用于联调）。 */
    public void assertContestant(UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        if (principal.role() != UserRole.CONTESTANT && principal.role() != UserRole.ADMIN) {
            throw new ForbiddenException("该操作仅限参赛账号");
        }
    }

    /** 每次下载统一校验可见性，避免猜测文件标识读取未公开题目。 */
    public void assertDownloadAllowed(UserPrincipal principal,
            com.qaqxianyuqaq.backend.domain.entity.Challenge challenge,
            com.qaqxianyuqaq.backend.domain.entity.EventConfig event) {
        assertAuthenticated(principal);
        if (principal.isAdmin()) {
            return;
        }
        java.time.LocalDateTime now = com.qaqxianyuqaq.backend.util.TimeUtil.now();
        if (challenge == null || event == null
                || challenge.getStatus() != com.qaqxianyuqaq.backend.domain.enums.ChallengeStatus.PUBLISHED
                || event.getStatus() != com.qaqxianyuqaq.backend.domain.enums.EventStatus.OPEN
                || (event.getStartsAt() != null && now.isBefore(event.getStartsAt()))
                || (event.getEndsAt() != null && !now.isBefore(event.getEndsAt()))) {
            throw new com.qaqxianyuqaq.backend.exception.NotFoundException("文件不存在或当前不可访问");
        }
    }

    /** 断言传入主体非空。 */
    public void assertAuthenticated(UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
    }
}

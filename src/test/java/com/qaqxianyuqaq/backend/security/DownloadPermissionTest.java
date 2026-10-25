package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.domain.entity.Challenge;
import com.qaqxianyuqaq.backend.domain.entity.EventConfig;
import com.qaqxianyuqaq.backend.domain.enums.*;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 下载签名权限的发布状态与时间窗口回归。 */
class DownloadPermissionTest {
    @Test void shouldRestrictContestantDownloadsAndAllowAdministrator() {
        PermissionAssert guard = new PermissionAssert();
        UserPrincipal contestant = new UserPrincipal("u", "test", UserRole.CONTESTANT, null, 0);
        UserPrincipal admin = new UserPrincipal("a", "admin", UserRole.ADMIN, null, 0);
        Challenge c = new Challenge(); c.setStatus(ChallengeStatus.DRAFT);
        EventConfig e = new EventConfig(); e.setStatus(EventStatus.OPEN);
        assertThatThrownBy(() -> guard.assertDownloadAllowed(contestant, c, e)).isInstanceOf(NotFoundException.class);
        guard.assertDownloadAllowed(admin, c, e);
        c.setStatus(ChallengeStatus.PUBLISHED); guard.assertDownloadAllowed(contestant, c, e);
        e.setEndsAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1));
        assertThatThrownBy(() -> guard.assertDownloadAllowed(contestant, c, e)).isInstanceOf(NotFoundException.class);
    }
}

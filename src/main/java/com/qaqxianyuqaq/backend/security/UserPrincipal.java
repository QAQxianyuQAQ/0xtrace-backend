package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** 当前登录主体：承载账号标识、角色与队伍信息。 */
public class UserPrincipal implements UserDetails {

    private final String userId;

    private final String username;

    private final String passwordHash;

    private final UserRole role;

    private final AccountStatus status;

    private final String teamId;

    private final int tokenVersion;

    /** 由账号实体构造登录主体。 */
    public UserPrincipal(User user) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.status = user.getStatus();
        this.teamId = user.getTeamId();
        this.tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /** 由令牌声明构造登录主体，用于免数据库加载的轻量校验场景。 */
    public UserPrincipal(String userId, String username, UserRole role, String teamId, int tokenVersion) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = null;
        this.role = role;
        this.status = AccountStatus.ACTIVE;
        this.teamId = teamId;
        this.tokenVersion = tokenVersion;
    }

    public String userId() {
        return userId;
    }

    public UserRole role() {
        return role;
    }

    public String teamId() {
        return teamId;
    }

    public int tokenVersion() {
        return tokenVersion;
    }

    /** 是否管理员。 */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == AccountStatus.ACTIVE;
    }
}

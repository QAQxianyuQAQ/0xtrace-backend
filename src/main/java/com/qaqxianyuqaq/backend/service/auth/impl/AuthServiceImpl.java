package com.qaqxianyuqaq.backend.service.auth.impl;

import com.qaqxianyuqaq.backend.domain.dto.auth.LoginDTO;
import com.qaqxianyuqaq.backend.domain.vo.auth.AuthSessionVO;
import com.qaqxianyuqaq.backend.domain.vo.auth.LoginVO;
import com.qaqxianyuqaq.backend.domain.vo.auth.MeVO;
import com.qaqxianyuqaq.backend.security.IssuedToken;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.entity.Team;
import com.qaqxianyuqaq.backend.domain.entity.User;
import com.qaqxianyuqaq.backend.domain.enums.AccountStatus;
import com.qaqxianyuqaq.backend.exception.ErrorCode;
import com.qaqxianyuqaq.backend.exception.ForbiddenException;
import com.qaqxianyuqaq.backend.exception.NotFoundException;
import com.qaqxianyuqaq.backend.exception.UnauthorizedException;
import com.qaqxianyuqaq.backend.repository.user.UserMapper;
import com.qaqxianyuqaq.backend.security.JwtTokenProvider;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.auth.AuthService;
import com.qaqxianyuqaq.backend.service.contestant.TeamService;
import com.qaqxianyuqaq.backend.service.cache.RateLimitService;
import com.qaqxianyuqaq.backend.service.cache.SessionService;
import com.qaqxianyuqaq.backend.service.event.EventService;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 认证服务实现。 */
@Service
public class AuthServiceImpl implements AuthService {

    /** 未知账号时使用的占位散列，用于抵消账号枚举的时序差异。 */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5B0N9RWOcZ9bjNqkQe0Q0NqkQe0Q0";

    private final AuthenticationManager authenticationManager;

    private final JwtTokenProvider jwtTokenProvider;

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final RateLimitService rateLimitService;

    private final SessionService sessionService;

    private final TraceQuestProperties properties;

    private final TeamService teamService;

    private final EventService eventService;

    public AuthServiceImpl(AuthenticationManager authenticationManager, JwtTokenProvider jwtTokenProvider,
                           UserMapper userMapper, PasswordEncoder passwordEncoder,
                           RateLimitService rateLimitService, SessionService sessionService,
                           TraceQuestProperties properties, TeamService teamService,
                           EventService eventService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.sessionService = sessionService;
        this.properties = properties;
        this.teamService = teamService;
        this.eventService = eventService;
    }

    @Override
    @Transactional
    public AuthSessionVO login(LoginDTO request, String clientIp) {
        String username = request.username().trim();

        // 1. 按客户端地址限流，抵御暴力破解
        rateLimitService.checkAndConsume("login", clientIp, properties.getRateLimit().getLoginPerMinute());

        // 2. 命中临时锁定窗口时直接拒绝，避免继续校验密码
        if (rateLimitService.isLoginLocked(clientIp, username)) {
            throw new com.qaqxianyuqaq.backend.exception.RateLimitedException(
                    properties.getRateLimit().getLoginFailureWindow().toSeconds());
        }

        // 3. 查询账号；账号不存在时执行一次占位散列校验以抵消时序差异
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            rateLimitService.recordLoginFailure(clientIp, username);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, "账号或密码错误");
        }

        // 4. 停用账号不参与认证，返回明确无权限提示
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ForbiddenException(ErrorCode.ACCOUNT_DISABLED, "账号已停用，请联系赛事管理员");
        }

        // 5. 交给 Spring Security 校验密码，失败时累计失败次数
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (AuthenticationException exception) {
            rateLimitService.recordLoginFailure(clientIp, username);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, "账号或密码错误");
        }

        // 6. 登录成功，清除失败计数并刷新最近登录时间
        rateLimitService.clearLoginFailure(clientIp, username);
        user.setLastLoginAt(TimeUtil.now());
        userMapper.updateById(user);

        // 7. 基于账号当前会话版本签发 JWT，令牌由控制器写入 HttpOnly Cookie
        UserPrincipal principal = new UserPrincipal(user);
        IssuedToken issued = jwtTokenProvider.issue(principal);
        Team team = resolveCurrentTeam(user);
        LoginVO loginVO = new LoginVO(user.getId(), user.getUsername(),
                user.getRole().apiValue(), team == null ? null : team.getId(), issued.expiresAt());
        return new AuthSessionVO(issued.token(), issued.jwtId(),
                properties.getJwt().getTtl().toSeconds(), loginVO);
    }

    @Override
    public void logout(String jwtId, long remainingSeconds) {
        // 1. 将当前令牌加入黑名单，剩余有效期内不可再次使用
        sessionService.revoke(jwtId, remainingSeconds);
    }

    @Override
    public MeVO me(UserPrincipal principal) {
        // 1. 以令牌主体为准回查账号，确保返回停用后的最新状态
        User user = userMapper.selectById(principal.userId());
        if (user == null) {
            throw new UnauthorizedException("账号不存在或已被删除");
        }

        // 2. 补齐当前赛事的队伍与展示字段
        Team team = resolveCurrentTeam(user);
        return new MeVO(user.getId(), user.getUsername(), user.getRole().apiValue(),
                team == null ? null : team.getId(), team == null ? null : team.getName(),
                user.getNickname(), user.getAvatarUrl(),
                user.getStatus().apiValue(), user.getLastLoginAt());
    }

    /** 查询当前赛事队伍；尚未配置赛事或未组队时返回 null。 */
    private Team resolveCurrentTeam(User user) {
        if (user.getRole() != com.qaqxianyuqaq.backend.domain.enums.UserRole.CONTESTANT) {
            return null;
        }
        try {
            return teamService.findMembership(eventService.requireCurrent().getId(), user.getId());
        } catch (NotFoundException exception) {
            return null;
        }
    }
}

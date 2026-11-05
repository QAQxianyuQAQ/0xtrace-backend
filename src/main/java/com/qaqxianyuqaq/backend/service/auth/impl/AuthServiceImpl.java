package com.qaqxianyuqaq.backend.service.auth.impl;

import com.qaqxianyuqaq.backend.domain.dto.auth.LoginDTO;
import com.qaqxianyuqaq.backend.domain.vo.auth.AuthSessionVO;
import com.qaqxianyuqaq.backend.domain.vo.auth.LoginVO;
import com.qaqxianyuqaq.backend.domain.vo.auth.MeVO;
import com.qaqxianyuqaq.backend.security.IssuedToken;
import com.qaqxianyuqaq.backend.security.NjuptAuthenticationClient;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.exception.BusinessException;
import java.util.Locale;

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

    private final NjuptAuthenticationClient njuptClient;

    private final NjuptAccountService njuptAccounts;

    public AuthServiceImpl(AuthenticationManager authenticationManager, JwtTokenProvider jwtTokenProvider,
                           UserMapper userMapper, PasswordEncoder passwordEncoder,
                           RateLimitService rateLimitService, SessionService sessionService,
                           TraceQuestProperties properties, TeamService teamService,
                           EventService eventService, NjuptAuthenticationClient njuptClient,
                           NjuptAccountService njuptAccounts) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.sessionService = sessionService;
        this.properties = properties;
        this.teamService = teamService;
        this.eventService = eventService;
        this.njuptClient = njuptClient;
        this.njuptAccounts = njuptAccounts;
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
        if (user == null || username.regionMatches(true, 0, "njupt:", 0, 6)) {
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

        // 6. 登录成功后清除失败计数并签发本站会话。
        rateLimitService.clearLoginFailure(clientIp, username);
        return issueSession(user);
    }

    @Override
    public AuthSessionVO loginNjupt(LoginDTO request, String clientIp) {
        // 1. 未启用学校认证时关闭入口，学号统一规范化以避免大小写重复账号。
        if (!properties.getNjupt().isEnabled()) throw new ForbiddenException("学校认证尚未启用");
        String subject = request.username().trim().toUpperCase(Locale.ROOT);
        if (!subject.matches("[A-Z0-9][A-Z0-9._-]{0,31}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "学号格式无效");
        }
        String key = "njupt:" + subject;

        // 2. 学校与本地登录共用地址限流，失败锁定按认证身份隔离。
        rateLimitService.checkAndConsume("login", clientIp, properties.getRateLimit().getLoginPerMinute());
        if (rateLimitService.isLoginLocked(clientIp, key)) {
            throw new com.qaqxianyuqaq.backend.exception.RateLimitedException(
                    properties.getRateLimit().getLoginFailureWindow().toSeconds());
        }

        // 3. 仅在学校认证成功后创建本站身份，网络故障和验证码要求不视为密码错误。
        try {
            njuptClient.authenticate(subject, request.password());
        } catch (UnauthorizedException exception) {
            rateLimitService.recordLoginFailure(clientIp, key);
            throw exception;
        }
        User user = njuptAccounts.resolve(subject);

        // 4. 停用账号和管理员身份禁止通过学校入口登录。
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ForbiddenException(ErrorCode.ACCOUNT_DISABLED, "账号已停用，请联系赛事管理员");
        }
        if (user.getRole() != UserRole.CONTESTANT) throw new ForbiddenException("学校登录仅供普通选手使用");

        // 5. 丢弃学校令牌，只签发沿用现有权限规则的本站会话。
        rateLimitService.clearLoginFailure(clientIp, key);
        return issueSession(user);
    }

    private AuthSessionVO issueSession(User user) {
        // 1. 刷新本站最近登录时间，保留账号原有队伍及会话版本。
        user.setLastLoginAt(TimeUtil.now());
        userMapper.updateById(user);

        // 2. 基于当前赛事队伍签发本站 JWT，会话仍使用本站权限与版本。
        IssuedToken issued = jwtTokenProvider.issue(new UserPrincipal(user));
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

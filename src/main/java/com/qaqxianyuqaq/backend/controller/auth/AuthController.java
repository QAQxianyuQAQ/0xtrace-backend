package com.qaqxianyuqaq.backend.controller.auth;

import com.qaqxianyuqaq.backend.domain.dto.auth.LoginDTO;
import com.qaqxianyuqaq.backend.domain.vo.auth.AuthSessionVO;
import com.qaqxianyuqaq.backend.domain.vo.auth.LoginVO;
import com.qaqxianyuqaq.backend.domain.vo.auth.MeVO;
import com.qaqxianyuqaq.backend.security.ParsedToken;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.security.JwtTokenProvider;
import com.qaqxianyuqaq.backend.security.SessionTokenResolver;
import com.qaqxianyuqaq.backend.security.UserPrincipal;
import com.qaqxianyuqaq.backend.service.auth.AuthService;
import com.qaqxianyuqaq.backend.util.RequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 认证接口。
 *
 * <p>令牌只通过 HttpOnly + SameSite=Strict Cookie 下发，响应体不携带任何凭证。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    private final TraceQuestProperties properties;

    private final SessionTokenResolver tokenResolver;

    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthService authService, TraceQuestProperties properties,
                          SessionTokenResolver tokenResolver, JwtTokenProvider jwtTokenProvider) {
        this.authService = authService;
        this.properties = properties;
        this.tokenResolver = tokenResolver;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** 登录并写入会话 Cookie。 */
    @PostMapping("/login")
    public LoginVO login(@Valid @RequestBody LoginDTO request, HttpServletRequest httpRequest,
                                  HttpServletResponse httpResponse) {
        // 1. 校验账号密码并签发令牌
        AuthSessionVO session = authService.login(request, RequestUtil.clientIp(httpRequest));

        // 2. 令牌写入 HttpOnly Cookie，禁止前端脚本读取
        ResponseCookie cookie = ResponseCookie.from(properties.getSecurity().getCookieName(), session.token())
                .httpOnly(true)
                .secure(properties.getSecurity().isCookieSecure())
                .sameSite(properties.getSecurity().getCookieSameSite())
                .path("/")
                .maxAge(Duration.ofSeconds(session.ttlSeconds()))
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return session.user();
    }

    /** 登出：令牌进入黑名单并清除 Cookie。 */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 1. 解析当前令牌并加入黑名单
        tokenResolver.resolveRaw(httpRequest).ifPresent(token -> {
            ParsedToken parsed = jwtTokenProvider.parse(token);
            if (parsed != null) {
                authService.logout(parsed.jwtId(), jwtTokenProvider.remainingSeconds(parsed));
            }
        });

        // 2. 立即清除客户端 Cookie
        ResponseCookie expired = ResponseCookie.from(properties.getSecurity().getCookieName(), "")
                .httpOnly(true)
                .secure(properties.getSecurity().isCookieSecure())
                .sameSite(properties.getSecurity().getCookieSameSite())
                .path("/")
                .maxAge(0)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return Map.of("ok", true);
    }

    /** 当前会话信息。 */
    @GetMapping("/me")
    public MeVO me(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.me(principal);
    }
}

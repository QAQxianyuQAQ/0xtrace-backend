package com.qaqxianyuqaq.backend.config;

import com.qaqxianyuqaq.backend.security.JwtAuthenticationFilter;
import com.qaqxianyuqaq.backend.security.RestAccessDeniedHandler;
import com.qaqxianyuqaq.backend.security.RestAuthenticationEntryPoint;
import com.qaqxianyuqaq.backend.security.SameOriginFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置。
 *
 * <p>认证统一走 Spring Security + JWT；会话无状态，令牌只存放于 HttpOnly Cookie，写请求叠加同源校验。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 密码编码器：BCrypt。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 认证管理器：使用数据库账号与 BCrypt 校验。 */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /** 安全过滤链：业务读取要求登录，后台接口要求管理员角色。 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                                   SameOriginFilter sameOriginFilter,
                                                   RestAuthenticationEntryPoint entryPoint,
                                                   RestAccessDeniedHandler deniedHandler) throws Exception {
        http
                // 无状态 JWT + SameSite=Strict Cookie，配合同源过滤器替代 CSRF 令牌
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查与登录接口公开
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/njupt/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/providers").permitAll()
                        // 赛事、题目与榜单不得绕过登录直接批量读取
                        .requestMatchers(HttpMethod.GET, "/api/v1/event", "/api/v1/challenges",
                                "/api/v1/challenges/*", "/api/v1/leaderboard").authenticated()
                        // WebSocket 握手由一次性票据拦截器鉴权
                        .requestMatchers(HttpMethod.GET, "/api/v1/challenge-connections").permitAll()
                        // 头像写入只认当前身份，读取要求登录并由服务校验有效版本
                        .requestMatchers(HttpMethod.POST, "/api/v1/me/avatar").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/avatars/*/*").authenticated()
                        // 后台接口统一要求管理员角色
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        // 其余接口（提交、个人资料、文件下载）要求已登录
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(sameOriginFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

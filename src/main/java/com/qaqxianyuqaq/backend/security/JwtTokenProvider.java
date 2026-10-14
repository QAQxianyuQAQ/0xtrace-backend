package com.qaqxianyuqaq.backend.security;

import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.domain.enums.UserRole;
import com.qaqxianyuqaq.backend.util.DigestUtil;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import com.qaqxianyuqaq.backend.util.TimeUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与解析组件。
 *
 * <p>令牌使用 HS256 签名，声明中包含角色、队伍与会话版本号，便于停用账号后立即失效。</p>
 */
@Component
public class JwtTokenProvider {

    private final TraceQuestProperties properties;

    private final SecretKey signingKey;

    public JwtTokenProvider(TraceQuestProperties properties) {
        this.properties = properties;
        // 由配置密钥派生出固定 256bit 签名密钥，短密钥不会导致启动失败
        this.signingKey = Keys.hmacShaKeyFor(
                DigestUtil.sha256(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8)));
    }

    /** 为指定主体签发令牌。 */
    public IssuedToken issue(UserPrincipal principal) {
        LocalDateTime issuedAt = TimeUtil.now();
        LocalDateTime expiresAt = issuedAt.plus(properties.getJwt().getTtl());
        String jwtId = IdGenerator.next("jti");
        String token = Jwts.builder()
                .setId(jwtId)
                .setSubject(principal.userId())
                .setIssuer(properties.getJwt().getIssuer())
                // JWT 规范基于 UTC 时间戳，这里把 UTC 墙钟时间转换为时间点
                .setIssuedAt(Date.from(issuedAt.toInstant(ZoneOffset.UTC)))
                .setExpiration(Date.from(expiresAt.toInstant(ZoneOffset.UTC)))
                .claim("username", principal.getUsername())
                .claim("role", principal.role().name())
                .claim("teamId", principal.teamId())
                .claim("ver", principal.tokenVersion())
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
        return new IssuedToken(token, jwtId, expiresAt);
    }

    /** 解析并校验令牌，非法令牌返回 null。 */
    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .requireIssuer(properties.getJwt().getIssuer())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String role = claims.get("role", String.class);
            Date expiration = claims.getExpiration();
            return new ParsedToken(
                    claims.getId(),
                    claims.getSubject(),
                    claims.get("username", String.class),
                    role == null ? null : UserRole.valueOf(role),
                    claims.get("teamId", String.class),
                    claims.get("ver", Integer.class) == null ? 0 : claims.get("ver", Integer.class),
                    expiration == null ? null
                            : LocalDateTime.ofInstant(expiration.toInstant(), ZoneOffset.UTC));
        } catch (JwtException | IllegalArgumentException exception) {
            return null;
        }
    }

    /** 令牌剩余有效时长（秒），用于黑名单过期时间。 */
    public long remainingSeconds(ParsedToken token) {
        if (token.expiresAt() == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.SECONDS.between(TimeUtil.now(), token.expiresAt()));
    }
}

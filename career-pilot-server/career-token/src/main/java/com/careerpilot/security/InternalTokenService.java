package com.careerpilot.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 网关 → 下游服务的内部身份令牌（阶段 3）。
 *
 * <p>外部用户 JWT 只到网关为止；网关验签后签发一个短时、受众限定的内部令牌，
 * 下游只信任该令牌的签名与 claims。这样：
 * <ul>
 *   <li>客户端无法通过伪造 {@code X-User-Id} 越过网关；</li>
 *   <li>内部令牌与用户令牌使用不同派生密钥，不能互换；</li>
 *   <li>令牌 5 分钟过期、受众限定，降低重放价值。</li>
 * </ul>
 *
 * <p>密钥缺省从 {@code app.jwt.secret} 派生（SHA-256，加固定后缀），
 * 也可用 {@code app.internal-token.secret} 显式覆盖；网关与下游必须一致。
 */
@Component
public class InternalTokenService {

    /** 网关注入下游的身份头 */
    public static final String HEADER = "X-Internal-Token";

    /** 内部令牌的固定 scope */
    public static final String SCOPE = "gateway";

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;

    private final long ttlSeconds;

    private final String audience;

    public InternalTokenService(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.internal-token.secret:}") String configuredSecret,
            @Value("${app.internal-token.ttl-seconds:300}") long ttlSeconds,
            @Value("${app.internal-token.audience:career-pilot-server}") String audience) {

        String seed = (configuredSecret == null || configuredSecret.isBlank())
                ? jwtSecret + ":internal" : configuredSecret;
        if (seed.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES
                && configuredSecret != null && !configuredSecret.isBlank()) {
            throw new IllegalStateException(
                    "app.internal-token.secret 不足 " + MIN_SECRET_BYTES + " 字节（256 bit）");
        }
        this.key = Keys.hmacShaKeyFor(sha256(seed));
        this.ttlSeconds = Math.max(30, ttlSeconds);
        this.audience = audience;
    }

    /** 签发内部令牌：sub = userId，附带 username 与 scope */
    public String issue(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlSeconds * 1000L);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("scope", SCOPE)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验内部令牌：签名、过期、scope、受众。
     *
     * @throws JwtException 任一校验失败
     */
    public Claims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!SCOPE.equals(claims.get("scope", String.class))) {
            throw new JwtException("内部令牌 scope 不匹配");
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(audience)) {
            throw new JwtException("内部令牌受众不匹配");
        }
        return claims;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}

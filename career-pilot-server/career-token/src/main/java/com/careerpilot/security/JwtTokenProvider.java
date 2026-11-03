package com.careerpilot.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 生成与解析。
 *
 * 约定依据《系统架构设计》7 节与《API接口设计》2 节：
 * - HS256 算法，密钥不少于 32 字节（256 bit），从配置注入，不写入仓库；
 * - Token 只承载用户标识，不含密码或业务数据；
 * - 服务端不实现撤销机制，退出登录由前端清除 Token 完成。
 */
@Component
public class JwtTokenProvider {

    private static final int MIN_KEY_BYTES = 32;

    private final SecretKey key;

    private final long expirationSeconds;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-seconds:7200}") long expirationSeconds) {

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_BYTES) {
            // 启动即失败，避免带着弱密钥运行
            throw new IllegalStateException(
                    "JWT 密钥不足 " + MIN_KEY_BYTES + " 字节（256 bit），请检查配置项 app.jwt.secret");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationSeconds = expirationSeconds;
    }

    /** 生成 Token：sub 为用户名，附带 userId */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationSeconds * 1000L);
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(expiry)
                // 显式指定 HS256：signWith(Key) 会按键长自动选算法，
                // 本项目的 64 字节密钥会被选为 HS512，与设计约定不一致
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并校验签名与有效期；任何无效情况都抛出 {@link JwtException} */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

}

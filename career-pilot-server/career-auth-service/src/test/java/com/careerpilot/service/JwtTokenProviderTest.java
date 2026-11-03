package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.careerpilot.security.JwtTokenProvider;

import io.jsonwebtoken.Claims;

/**
 * 用户 JWT 的 jti（阶段 4）：登出黑名单依赖每次签发的唯一标识。
 */
class JwtTokenProviderTest {

    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void generateToken_includesUniqueJti() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60);

        Claims claims = provider.parse(provider.generateToken(7L, "alice"));

        assertNotNull(claims.getId());
        assertEquals("alice", claims.getSubject());
        assertEquals(7L, claims.get("userId", Number.class).longValue());
    }

    @Test
    void twoTokensHaveDifferentJti() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60);

        String first = provider.parse(provider.generateToken(7L, "alice")).getId();
        String second = provider.parse(provider.generateToken(7L, "alice")).getId();

        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
    }
}

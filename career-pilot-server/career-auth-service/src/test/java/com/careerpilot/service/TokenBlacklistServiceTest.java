package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.careerpilot.security.TokenBlacklistService;

/**
 * 登出撤销的黑名单语义（阶段 4）：按 jti 写入、按 jti 查询、Redis 故障 fail-open。
 */
class TokenBlacklistServiceTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final TokenBlacklistService service = new TokenBlacklistService(redis);

    @Test
    void blacklist_writesJtiWithRemainingTtl() {
        when(redis.opsForValue()).thenReturn(ops);

        service.blacklist("jti-1", 120);

        verify(ops).set(eq("cp:auth:jwt:blacklist:jti-1"), eq("1"), eq(Duration.ofSeconds(120)));
    }

    @Test
    void isBlacklisted_trueWhenKeyExists() {
        when(redis.hasKey("cp:auth:jwt:blacklist:jti-1")).thenReturn(true);
        assertTrue(service.isBlacklisted("jti-1"));
    }

    @Test
    void isBlacklisted_falseWhenKeyAbsent() {
        when(redis.hasKey(anyString())).thenReturn(false);
        assertFalse(service.isBlacklisted("jti-2"));
    }

    @Test
    void isBlacklisted_failOpenWhenRedisDown() {
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertFalse(service.isBlacklisted("jti-3"));
    }

    @Test
    void blankJtiIsIgnored() {
        service.blacklist(null, 60);
        service.blacklist("  ", 60);
        assertFalse(service.isBlacklisted(null));
    }
}

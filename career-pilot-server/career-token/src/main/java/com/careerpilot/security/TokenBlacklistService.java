package com.careerpilot.security;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 用户 JWT 的登出撤销（阶段 4）：按 jti 写入 Redis，TTL = 令牌剩余有效期。
 *
 * <p>网关在验签后查询黑名单；Redis 不可用时 **fail-open**（放行并告警），
 * 避免缓存故障导致全站无法登录。TTL 到期后自动清理，不会无限增长。
 */
@Component
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    static final String KEY_PREFIX = "cp:auth:jwt:blacklist:";

    private final StringRedisTemplate redis;

    public TokenBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void blacklist(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank() || ttlSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
        }
        catch (Exception ex) {
            log.warn("JWT 黑名单查询失败（fail-open）：{}", ex.getClass().getSimpleName());
            return false;
        }
    }
}

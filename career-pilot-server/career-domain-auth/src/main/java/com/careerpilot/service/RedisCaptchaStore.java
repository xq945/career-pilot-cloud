package com.careerpilot.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 验证码存储（阶段 4）：多实例共享，TTL 由 Redis 负责，一次性消费用 GETDEL。
 */
final class RedisCaptchaStore implements CaptchaStore {

    static final String KEY_PREFIX = "cp:auth:captcha:";

    private final StringRedisTemplate redis;

    RedisCaptchaStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String captchaId, String code, int ttlSeconds) {
        redis.opsForValue().set(KEY_PREFIX + captchaId, code, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String take(String captchaId) {
        return redis.opsForValue().getAndDelete(KEY_PREFIX + captchaId);
    }

    @Override
    public void remove(String captchaId) {
        redis.delete(KEY_PREFIX + captchaId);
    }

    @Override
    public void evictExpired() {
        // Redis TTL 自动清理
    }

    @Override
    public int size() {
        // 生产不依赖该值；容量由 Redis maxmemory 策略负责
        return 0;
    }
}

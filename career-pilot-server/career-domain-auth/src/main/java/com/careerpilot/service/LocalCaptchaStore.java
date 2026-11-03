package com.careerpilot.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内验证码存储：单机/测试使用，保留原有的一次性、TTL 与容量淘汰语义。
 */
final class LocalCaptchaStore implements CaptchaStore {

    private static final Logger log = LoggerFactory.getLogger(LocalCaptchaStore.class);

    private final int maxSize;

    private final Clock clock;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    LocalCaptchaStore(int maxSize, Clock clock) {
        this.maxSize = maxSize;
        this.clock = clock;
    }

    @Override
    public void save(String captchaId, String code, int ttlSeconds) {
        evictIfFull();
        store.put(captchaId, new Entry(code, clock.instant().plusSeconds(ttlSeconds)));
    }

    @Override
    public String take(String captchaId) {
        Entry entry = store.remove(captchaId);
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
            return null;
        }
        return entry.code();
    }

    @Override
    public void remove(String captchaId) {
        store.remove(captchaId);
    }

    @Override
    public void evictExpired() {
        Instant now = clock.instant();
        int before = store.size();
        store.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        int removed = before - store.size();
        if (removed > 0) {
            log.debug("验证码缓存清理：移除 {} 条过期项，剩余 {} 条", removed, store.size());
        }
    }

    @Override
    public int size() {
        return store.size();
    }

    private void evictIfFull() {
        if (store.size() < maxSize) {
            return;
        }
        evictExpired();
        if (store.size() < maxSize) {
            return;
        }
        Comparator<Map.Entry<String, Entry>> byExpiry =
                Comparator.comparing(entry -> entry.getValue().expiresAt());
        store.entrySet().stream().min(byExpiry).ifPresent(oldest -> store.remove(oldest.getKey()));
        log.warn("验证码缓存已达容量上限 {}，淘汰最早过期的一条", maxSize);
    }

    private record Entry(String code, Instant expiresAt) {
    }
}

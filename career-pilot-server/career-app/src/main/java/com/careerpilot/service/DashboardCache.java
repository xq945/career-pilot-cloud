package com.careerpilot.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.careerpilot.dto.DashboardSummary;

/**
 * 仪表盘汇总的进程内短缓存（阶段 10）。
 *
 * <p>30 秒 TTL 是刻意的折中：工作台数字允许秒级延迟，换取一次加载只打一遍下游；
 * TTL 内的事件（如 {@code interview.completed}）由 RabbitMQ 消费者调用
 * {@link #invalidate(Long)} 精确失效，保证"自己刚做完的任务"立即可见。
 *
 * <p>多实例部署时各实例各自缓存；事件失效只作用于收到消息的实例。若需要跨实例一致，
 * 应换成 Redis 缓存（阶段 10 之后的演进项）。
 */
@Component
public class DashboardCache {

    private final long ttlMillis;

    private final int maxUsers;

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public DashboardCache(@Value("${app.dashboard.cache-ttl-seconds:30}") long ttlSeconds,
                          @Value("${app.dashboard.cache-max-users:1000}") int maxUsers) {
        this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
        this.maxUsers = maxUsers;
    }

    /** 命中且未过期时返回缓存；TTL <= 0 时禁用缓存 */
    public DashboardSummary get(Long userId) {
        if (ttlMillis <= 0) {
            return null;
        }
        Entry entry = entries.get(userId);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis() <= System.currentTimeMillis()) {
            entries.remove(userId, entry);
            return null;
        }
        return entry.summary();
    }

    public void put(Long userId, DashboardSummary summary) {
        if (ttlMillis <= 0) {
            return;
        }
        evictIfFull();
        entries.put(userId, new Entry(summary, System.currentTimeMillis() + ttlMillis));
    }

    /** 事件驱动失效（interview.completed / optimization.completed） */
    public void invalidate(Long userId) {
        entries.remove(userId);
    }

    /** 容量保护：先清过期项，仍满则整体清空（工作台缓存，清空的代价可接受） */
    private void evictIfFull() {
        if (maxUsers <= 0 || entries.size() < maxUsers) {
            return;
        }
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (entries.size() >= maxUsers) {
            entries.clear();
        }
    }

    /** 当前缓存条目数（单测与诊断用） */
    int size() {
        return entries.size();
    }

    private record Entry(DashboardSummary summary, long expiresAtMillis) {
    }
}

package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.DashboardSummary;

/**
 * 阶段 10：Dashboard 短缓存的 TTL、失效与容量保护。
 */
class DashboardCacheTest {

    @Test
    void get_returnsCachedValueWithinTtl() {
        DashboardCache cache = new DashboardCache(30, 100);
        DashboardSummary summary = new DashboardSummary();

        cache.put(7L, summary);

        assertSame(summary, cache.get(7L));
    }

    @Test
    void get_expiredEntryReturnsNull() throws InterruptedException {
        DashboardCache cache = new DashboardCache(1, 100);
        cache.put(7L, new DashboardSummary());

        Thread.sleep(1100);

        assertNull(cache.get(7L));
        assertEquals(0, cache.size());
    }

    @Test
    void invalidate_removesOnlyThatUsersEntry() {
        DashboardCache cache = new DashboardCache(30, 100);
        cache.put(7L, new DashboardSummary());
        cache.put(8L, new DashboardSummary());

        cache.invalidate(7L);

        assertNull(cache.get(7L));
        assertTrue(cache.get(8L) != null);
    }

    @Test
    void zeroTtl_disablesCache() {
        DashboardCache cache = new DashboardCache(0, 100);
        cache.put(7L, new DashboardSummary());

        assertNull(cache.get(7L));
        assertEquals(0, cache.size());
    }

    @Test
    void maxUsers_reached_clearsExpiredThenFallsBackToClear() {
        DashboardCache cache = new DashboardCache(60, 2);
        cache.put(1L, new DashboardSummary());
        cache.put(2L, new DashboardSummary());
        cache.put(3L, new DashboardSummary());

        assertTrue(cache.size() <= 2);
    }
}

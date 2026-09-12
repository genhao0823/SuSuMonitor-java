package com.susumonitor.server.common.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证固定窗口限流器：基本配额语义与桶表超阈值后的惰性淘汰
 * （防伪造 IP 撑爆内存；被淘汰 key 的窗口早已过期，重建新桶语义等价）。
 */
class FixedWindowRateLimiterTests {

    /** 可推进时钟：模拟窗口滚动。 */
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-12T00:00:00Z"));

        void advance(Duration duration) {
            now.set(now.get().plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffsetHolder.ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static final class ZoneOffsetHolder {
        private static final ZoneId ZONE = ZoneId.of("UTC");
    }

    /** 超过桶表阈值：未闲置的桶保留，闲置超 2 个窗口的桶被清理。 */
    @Test
    void purgeShouldRemoveIdleBucketsBeyondThreshold() {
        MutableClock clock = new MutableClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofSeconds(100), clock);

        // 填满桶表阈值并新增一个 key（触发阈值但无可清理项：全部刚访问过）。
        for (int i = 0; i <= FixedWindowRateLimiter.MAX_TRACKED_KEYS; i++) {
            assertTrue(limiter.tryAcquire("key-" + i));
        }
        assertEquals(FixedWindowRateLimiter.MAX_TRACKED_KEYS + 1, limiter.trackedKeyCount());

        // 时间推进 2.5 个窗口：既有桶全部闲置超阈值。
        clock.advance(Duration.ofSeconds(250));
        assertTrue(limiter.tryAcquire("fresh-key"));

        // 闲置桶全部被清理，仅剩本轮新访问的 key。
        assertEquals(1, limiter.trackedKeyCount());
    }

    /** 被清理后重新访问的 key 获得全新配额（窗口早已过期，语义等价）；未超阈值时不误删。 */
    @Test
    void purgedKeyShouldStartFreshWindow() {
        MutableClock clock = new MutableClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofSeconds(100), clock);

        // 填满桶表阈值；attacker 配额耗尽（未超阈值前桶不会被清理，仅因窗口过期自然回填）。
        for (int i = 0; i < FixedWindowRateLimiter.MAX_TRACKED_KEYS; i++) {
            assertTrue(limiter.tryAcquire("key-" + i));
        }
        assertTrue(limiter.tryAcquire("attacker"));
        assertFalse(limiter.tryAcquire("attacker"));

        // 推进 2.5 个窗口后触发清理：attacker 的闲置桶被删除，重新访问获得全新窗口。
        clock.advance(Duration.ofSeconds(250));
        assertTrue(limiter.tryAcquire("trigger"));
        assertTrue(limiter.tryAcquire("attacker"), "purged key should start a fresh window");
        assertEquals(2, limiter.trackedKeyCount());
    }
}

package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证消费耗时统计注册表的窗口聚合与快照语义。
 */
class ConsumeTimingStatsRegistryTests {

    private final ConsumeTimingStatsRegistry registry =
            new ConsumeTimingStatsRegistry(Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC));

    /** 多次记录应累积 count/total/max 并更新最近采样时刻。 */
    @Test
    void recordShouldAccumulateWindow() {
        registry.record("alert-notifier", 10);
        registry.record("alert-notifier", 30);
        registry.record("alert-notifier", 20);

        ConsumeTimingStatsRegistry.ConsumerTimingSnapshot stats = registry.snapshot().get("alert-notifier");
        assertEquals(3, stats.totalCount());
        assertEquals(60, stats.totalMs());
        assertEquals(30, stats.maxMs());
        assertTrue(stats.lastSampleAt() != null);
    }

    /** 快照应只包含有样本的消费者，且返回不可变副本（修改不影响注册表）。 */
    @Test
    void snapshotShouldBeImmutableAndConsumerScoped() {
        registry.record("alert-evaluator", 5);

        Map<String, ConsumeTimingStatsRegistry.ConsumerTimingSnapshot> snapshot = registry.snapshot();
        assertEquals(1, snapshot.size());
        assertThrowsUnsupported(() -> snapshot.put("x", null));
        assertEquals(1, registry.snapshot().get("alert-evaluator").totalCount());
    }

    private void assertThrowsUnsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // 预期快照不可变。
        }
    }
}

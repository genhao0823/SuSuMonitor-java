package com.susumonitor.server.module.metrics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.module.metrics.vo.ProcessSnapshotVo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证进程快照注册表的最新覆盖与 90 秒新鲜窗口过期语义。 */
class ProcessSnapshotRegistryTests {

    /** 可手动推进的可控时钟，隔离过期判定与真实时间。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-15T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        /** 推进时钟。 */
        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    /** 新鲜窗口内写入即可读到，重复写入取最新一条。 */
    @Test
    void updateShouldOverwriteAndReturnLatestSnapshot() {
        MutableClock clock = new MutableClock();
        ProcessSnapshotRegistry registry = new ProcessSnapshotRegistry(clock);
        registry.update(snapshot(1001L, 1));
        clock.advance(Duration.ofSeconds(5));
        registry.update(snapshot(1001L, 2));

        ProcessSnapshotVo latest = registry.latest(1001L).orElseThrow();
        assertEquals(2, latest.cpuTop().size());
    }

    /** 超过 90 秒新鲜窗口后快照视为过期，读取为空（恰好到达窗口边界仍可见）。 */
    @Test
    void snapshotShouldExpireAfterFreshnessWindow() {
        MutableClock clock = new MutableClock();
        ProcessSnapshotRegistry registry = new ProcessSnapshotRegistry(clock);
        registry.update(snapshot(1001L, 1));

        clock.advance(ProcessSnapshotRegistry.FRESHNESS_WINDOW.plusSeconds(1));
        assertTrue(registry.latest(1001L).isEmpty(),
                "snapshot should expire once the freshness window is exceeded");
    }

    /** 快照在新鲜窗口边界内仍然可读。 */
    @Test
    void snapshotShouldStayVisibleWithinWindow() {
        MutableClock clock = new MutableClock();
        ProcessSnapshotRegistry registry = new ProcessSnapshotRegistry(clock);
        registry.update(snapshot(1001L, 1));

        clock.advance(ProcessSnapshotRegistry.FRESHNESS_WINDOW.minusSeconds(1));
        assertTrue(registry.latest(1001L).isPresent());
    }

    /** 不同服务器的快照相互隔离；无记录服务器读取为空。 */
    @Test
    void unknownServerShouldReturnEmpty() {
        ProcessSnapshotRegistry registry = new ProcessSnapshotRegistry(new MutableClock());
        registry.update(snapshot(1001L, 1));

        assertTrue(registry.latest(9999L).isEmpty());
    }

    /** 构造带指定条数 CPU 排行的快照。 */
    private ProcessSnapshotVo snapshot(long serverId, int cpuEntries) {
        List<com.susumonitor.server.module.metrics.vo.ProcessSampleVo> cpuTop =
                new java.util.ArrayList<>();
        for (int i = 0; i < cpuEntries; i++) {
            cpuTop.add(new com.susumonitor.server.module.metrics.vo.ProcessSampleVo(
                    (int) (serverId + i), "proc-" + i, java.math.BigDecimal.ONE, java.math.BigDecimal.TEN));
        }
        return new ProcessSnapshotVo(serverId, OffsetDateTime.now(ZoneOffset.UTC), cpuTop, List.of());
    }
}

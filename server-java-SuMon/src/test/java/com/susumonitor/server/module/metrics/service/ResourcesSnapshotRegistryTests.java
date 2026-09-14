package com.susumonitor.server.module.metrics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.module.metrics.vo.DiskSampleVo;
import com.susumonitor.server.module.metrics.vo.NicSampleVo;
import com.susumonitor.server.module.metrics.vo.ServerResourcesSnapshotVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证资源快照注册表的最新覆盖与 90 秒新鲜窗口过期语义（与进程快照注册表同构）。 */
class ResourcesSnapshotRegistryTests {

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
        ResourcesSnapshotRegistry registry = new ResourcesSnapshotRegistry(clock);
        registry.update(snapshot(1001L, 1));
        clock.advance(Duration.ofSeconds(5));
        registry.update(snapshot(1001L, 2));

        ServerResourcesSnapshotVo latest = registry.latest(1001L).orElseThrow();
        assertEquals(1, latest.disks().size());
        assertEquals("/mnt/d2", latest.disks().get(0).mountPoint());
    }

    /** 超过 90 秒新鲜窗口后快照视为过期，读取为空（恰好到达窗口边界仍可见）。 */
    @Test
    void snapshotShouldExpireAfterFreshnessWindow() {
        MutableClock clock = new MutableClock();
        ResourcesSnapshotRegistry registry = new ResourcesSnapshotRegistry(clock);
        registry.update(snapshot(1001L, 1));

        clock.advance(ResourcesSnapshotRegistry.FRESHNESS_WINDOW.plusSeconds(1));
        assertTrue(registry.latest(1001L).isEmpty(),
                "snapshot should expire once the freshness window is exceeded");
    }

    /** 快照在新鲜窗口边界内仍然可读。 */
    @Test
    void snapshotShouldStayVisibleWithinWindow() {
        MutableClock clock = new MutableClock();
        ResourcesSnapshotRegistry registry = new ResourcesSnapshotRegistry(clock);
        registry.update(snapshot(1001L, 1));

        clock.advance(ResourcesSnapshotRegistry.FRESHNESS_WINDOW.minusSeconds(1));
        assertTrue(registry.latest(1001L).isPresent());
    }

    /** 不同服务器的快照相互隔离；无记录服务器读取为空。 */
    @Test
    void unknownServerShouldReturnEmpty() {
        ResourcesSnapshotRegistry registry = new ResourcesSnapshotRegistry(new MutableClock());
        registry.update(snapshot(1001L, 1));

        assertTrue(registry.latest(9999L).isEmpty());
    }

    /** 构造带指定编号挂载点的资源快照。 */
    private ServerResourcesSnapshotVo snapshot(long serverId, int diskIndex) {
        DiskSampleVo disk = new DiskSampleVo("/mnt/d" + diskIndex, "/dev/sda" + diskIndex, 1024L, 256L);
        NicSampleVo nic = new NicSampleVo("eth0", BigDecimal.ONE, BigDecimal.TEN);
        return new ServerResourcesSnapshotVo(serverId, OffsetDateTime.now(ZoneOffset.UTC), List.of(disk),
                List.of(nic));
    }
}

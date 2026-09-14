package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.module.metrics.vo.ServerResourcesSnapshotVo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 资源快照内存注册表：保留每台服务器最新一次磁盘/网卡扩展资源快照。
 *
 * <p>与 {@link ProcessSnapshotRegistry} 同构：扩展资源是易变列表型信息，
 * 不落库（无对应表、无清理调度），每台服务器仅一条小记录；快照超过
 * {@link #FRESHNESS_WINDOW} 未更新即视为过期，读取侧惰性判定过期，
 * 无后台扫描线程（websocket-protocol.md v1.5）。</p>
 */
// 注册为单例组件：指标入库链路更新、REST 查询读取共享同一份内存状态。
@Component
public class ResourcesSnapshotRegistry {

    /** 快照新鲜窗口：与进程快照注册表一致，覆盖 Agent 默认 5 秒上报周期的掉线与短暂断连场景。 */
    static final Duration FRESHNESS_WINDOW = Duration.ofSeconds(90);

    private final Clock clock;
    private final ConcurrentHashMap<Long, SnapshotEntry> snapshots = new ConcurrentHashMap<>();

    /** 注入应用时钟，保证过期判定可测试。 */
    public ResourcesSnapshotRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记录或覆盖服务器最新资源快照。
     *
     * <p>由 MonitorMetricsPublisher 在事务提交后调用，只有已确认入库的
     * 快照才会进入注册表。</p>
     *
     * @param snapshot 最新资源快照
     */
    public void update(ServerResourcesSnapshotVo snapshot) {
        if (snapshot == null || snapshot.serverId() == null) {
            return;
        }
        snapshots.put(snapshot.serverId(), new SnapshotEntry(snapshot, clock.instant()));
    }

    /**
     * 查询服务器新鲜窗口内的最新资源快照。
     *
     * @param serverId 服务器 ID
     * @return 快照；无记录或已过期时为空
     */
    public Optional<ServerResourcesSnapshotVo> latest(Long serverId) {
        SnapshotEntry entry = snapshots.get(serverId);
        if (entry == null || Duration.between(entry.storedAt(), clock.instant()).compareTo(FRESHNESS_WINDOW) > 0) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    /** 注册表条目：快照本体与写入时刻。 */
    private record SnapshotEntry(ServerResourcesSnapshotVo snapshot, Instant storedAt) {
    }
}

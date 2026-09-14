package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.module.metrics.vo.ProcessSnapshotVo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 进程快照内存注册表：保留每台服务器最新一次 Top 进程快照。
 *
 * <p>进程数据是易变列表型信息，本设计不落库（无对应表、无清理调度）；
 * 每台服务器仅一条小记录，规模随活跃服务器数线性增长。快照超过
 * {@link #FRESHNESS_WINDOW} 未更新即视为过期，读取侧按无快照处理，
 * 避免把陈旧进程排行当作实时数据返回。</p>
 */
// 注册为单例组件：指标入库链路更新、REST 查询读取共享同一份内存状态。
@Component
public class ProcessSnapshotRegistry {

    /** 快照新鲜窗口：覆盖 Agent 默认 5 秒上报周期的掉线与短暂断连场景。 */
    // 90 秒 ≈ 18 个默认采集周期，Agent 掉线超过该窗口后快照不再对外可见。
    static final Duration FRESHNESS_WINDOW = Duration.ofSeconds(90);

    private final Clock clock;
    private final ConcurrentHashMap<Long, SnapshotEntry> snapshots = new ConcurrentHashMap<>();

    /** 注入应用时钟，保证过期判定可测试。 */
    public ProcessSnapshotRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记录或覆盖服务器最新进程快照。
     *
     * <p>由 MonitorMetricsPublisher 在事务提交后调用，只有已确认入库的
     * 快照才会进入注册表。</p>
     *
     * @param snapshot 最新进程快照
     */
    public void update(ProcessSnapshotVo snapshot) {
        if (snapshot == null || snapshot.serverId() == null) {
            return;
        }
        snapshots.put(snapshot.serverId(), new SnapshotEntry(snapshot, clock.instant()));
    }

    /**
     * 查询服务器新鲜窗口内的最新进程快照。
     *
     * @param serverId 服务器 ID
     * @return 快照；无记录或已过期时为空
     */
    public Optional<ProcessSnapshotVo> latest(Long serverId) {
        SnapshotEntry entry = snapshots.get(serverId);
        if (entry == null || Duration.between(entry.storedAt(), clock.instant()).compareTo(FRESHNESS_WINDOW) > 0) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    /** 注册表条目：快照本体与写入时刻。 */
    private record SnapshotEntry(ProcessSnapshotVo snapshot, Instant storedAt) {
    }
}

package com.susumonitor.server.module.system;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 队列积压快照注册表（MVP-14 监控收尾，单 JVM 内存态）。
 *
 * <p>保存最近一轮探测的每队列消息数与采样时刻；探测失败的队列标记 error。
 * 快照只读、被下一轮整体替换，与项目单 JVM 内存状态口径一致。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class QueueBacklogSnapshotRegistry {

    /** 单个队列的探测快照（不可变）。 */
    public record QueueSnapshot(String queue, long messages, OffsetDateTime checkedAt, boolean error) {
    }

    private volatile Map<String, QueueSnapshot> snapshots = Map.of();

    /**
     * 整体替换最近一轮探测快照。
     *
     * @param latest 队列名 → 快照
     */
    public void replaceAll(Map<String, QueueSnapshot> latest) {
        this.snapshots = Map.copyOf(latest);
    }

    /**
     * 返回最近一轮快照（不可变副本）。
     *
     * @return 队列名 → 快照；尚无探测结果时为空
     */
    public Map<String, QueueSnapshot> snapshot() {
        return snapshots;
    }
}

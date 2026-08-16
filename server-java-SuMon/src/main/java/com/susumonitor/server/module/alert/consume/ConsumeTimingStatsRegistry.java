package com.susumonitor.server.module.alert.consume;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费处理耗时统计注册表（MVP-14 监控收尾，单 JVM 内存滚动窗口）。
 *
 * <p>每个消费者维护一个累积窗口：消息总数、总耗时、最大耗时与最近采样时刻；
 * 统计随进程重启清零（窗口语义），多实例部署时各实例独立统计——与项目
 * 单 JVM 内存状态口径一致。窗口不做定时滚动，快照即为进程生命周期累积。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class ConsumeTimingStatsRegistry {

    /** 单个消费者的耗时窗口快照（不可变）。 */
    public record ConsumerTimingSnapshot(long totalCount, long totalMs, long maxMs,
            OffsetDateTime lastSampleAt) {
    }

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    private final Clock clock;

    /** 注入应用统一 UTC 时钟。 */
    public ConsumeTimingStatsRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记录一次消费处理的耗时（成功与失败尝试都记录）。
     *
     * @param consumer 消费者名（alert-evaluator / alert-notifier / alert-resolved-notifier）
     * @param durationMs 处理耗时（毫秒，非负）
     */
    public void record(String consumer, long durationMs) {
        windows.compute(consumer, (key, window) -> {
            Window current = window == null ? new Window() : window;
            current.totalCount += 1;
            current.totalMs += durationMs;
            current.maxMs = Math.max(current.maxMs, durationMs);
            current.lastSampleAt = OffsetDateTime.now(clock);
            return current;
        });
    }

    /**
     * 返回全部消费者的窗口快照（不可变副本），无样本的消费者不在结果内。
     *
     * @return consumer 名 → 窗口快照
     */
    public Map<String, ConsumerTimingSnapshot> snapshot() {
        Map<String, ConsumerTimingSnapshot> result = new java.util.HashMap<>();
        windows.forEach((consumer, window) -> result.put(consumer,
                new ConsumerTimingSnapshot(window.totalCount, window.totalMs, window.maxMs, window.lastSampleAt)));
        return Map.copyOf(result);
    }

    /** 单消费者累积窗口（可变内部状态，仅注册表线程内更新）。 */
    private static final class Window {
        private long totalCount;
        private long totalMs;
        private long maxMs;
        private OffsetDateTime lastSampleAt;
    }
}

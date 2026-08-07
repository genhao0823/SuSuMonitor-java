package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.metrics.outbox.OutboxCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理超过保留期的已发布 Outbox 记录，不处理 pending 重试事件。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.outbox-cleanup-enabled", havingValue = "true")
public class OutboxCleanupScheduler {

    private final OutboxCleanupService outboxCleanupService;

    /**
     * 构造 Outbox 清理调度器。
     *
     * @param outboxCleanupService Outbox 清理服务
     */
    public OutboxCleanupScheduler(OutboxCleanupService outboxCleanupService) {
        this.outboxCleanupService = outboxCleanupService;
    }

    /** 执行一次已发布 Outbox 清理；异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.rabbitmq.outbox-cleanup-cron}")
    public void cleanupExpiredPublishedOutbox() {
        try {
            outboxCleanupService.cleanupExpiredPublishedOutbox().ifPresentOrElse(
                    result -> log.info("Outbox cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("Outbox cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("Outbox cleanup failed", exception);
        }
    }
}

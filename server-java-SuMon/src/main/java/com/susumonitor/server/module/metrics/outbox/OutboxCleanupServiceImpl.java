package com.susumonitor.server.module.metrics.outbox;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分批清理超过保留期的已发布 Outbox 记录，并保证同一 JVM 内定时任务不重叠。
 */
@Service
@ConditionalOnProperty(name = "susumonitor.rabbitmq.outbox-cleanup-enabled", havingValue = "true")
public class OutboxCleanupServiceImpl implements OutboxCleanupService {

    private final OutboxCleanupMapper outboxCleanupMapper;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxCleanupServiceImpl(OutboxCleanupMapper outboxCleanupMapper, AppProperties appProperties,
            TransactionTemplate transactionTemplate) {
        this.outboxCleanupMapper = outboxCleanupMapper;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<CleanupResult> cleanupExpiredPublishedOutbox() {
        LocalDateTime cutoffTime = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getRabbitmq().getOutboxRetentionDays());
        return cleanupExpiredPublishedOutbox(cutoffTime);
    }

    @Override
    public Optional<CleanupResult> cleanupExpiredPublishedOutbox(LocalDateTime cutoffTime) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }

        long startedAt = System.nanoTime();
        int batchCount = 0;
        int deletedRows = 0;
        try {
            int maxBatches = appProperties.getRabbitmq().getOutboxCleanupMaxBatchesPerRun();
            int batchSize = appProperties.getRabbitmq().getOutboxCleanupBatchSize();
            while (batchCount < maxBatches) {
                Integer deleted = transactionTemplate.execute(status ->
                        outboxCleanupMapper.deletePublishedBeforeBatch(cutoffTime, batchSize));
                int currentDeleted = deleted == null ? 0 : deleted;
                if (currentDeleted == 0) {
                    break;
                }
                batchCount++;
                deletedRows += currentDeleted;
            }
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return Optional.of(new CleanupResult(cutoffTime, batchCount, deletedRows, durationMs));
        } finally {
            running.set(false);
        }
    }
}

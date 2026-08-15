package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.mapper.AlertNotificationCleanupMapper;
import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分批清理超过保留期的通知投递记录（alert_notifications），并保证同一 JVM 内定时任务不重叠。
 *
 * <p>补齐 V21 通知表"只增不删"的增长风险：清理按 created_at 分批删除，
 * 保留期、批大小与轮次上限均可配置，语义与其他清理服务（记录/Outbox/消费记录）一致。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.alert.notification-cleanup-enabled", havingValue = "true")
public class AlertNotificationCleanupServiceImpl implements AlertNotificationCleanupService {

    private final AlertNotificationCleanupMapper notificationCleanupMapper;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造通知投递清理服务。
     *
     * @param notificationCleanupMapper 通知投递清理 Mapper
     * @param appProperties 应用配置
     * @param transactionTemplate 每批独立事务模板
     */
    public AlertNotificationCleanupServiceImpl(AlertNotificationCleanupMapper notificationCleanupMapper,
            AppProperties appProperties, TransactionTemplate transactionTemplate) {
        this.notificationCleanupMapper = notificationCleanupMapper;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 清理当前保留周期之前的通知投递记录；已有任务运行时立即跳过。
     *
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredNotifications() {
        LocalDateTime cutoffTime = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getAlert().getNotificationRetentionDays());
        return cleanupExpiredNotifications(cutoffTime);
    }

    /**
     * 按指定边界执行清理，供独立数据库验收固定 cutoff 边界。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredNotifications(LocalDateTime cutoffTime) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }

        long startedAt = System.nanoTime();
        int batchCount = 0;
        int deletedRows = 0;
        try {
            int maxBatches = appProperties.getAlert().getNotificationCleanupMaxBatchesPerRun();
            int batchSize = appProperties.getAlert().getNotificationCleanupBatchSize();
            while (batchCount < maxBatches) {
                Integer deleted = transactionTemplate.execute(status ->
                        notificationCleanupMapper.deleteExpiredBatch(cutoffTime, batchSize));
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

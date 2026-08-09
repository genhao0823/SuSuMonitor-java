package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分批清理指标幂等接收记录（metrics_ingestions），并保证同一 JVM 内定时任务不重叠。
 */
@Slf4j
@Service
public class IngestionCleanupServiceImpl implements IngestionCleanupService {

    private final IngestionCleanupMapper ingestionCleanupMapper;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造指标幂等接收记录清理服务。
     *
     * @param ingestionCleanupMapper 接收记录清理 Mapper
     * @param appProperties 应用配置
     * @param transactionTemplate 每批独立事务模板
     */
    public IngestionCleanupServiceImpl(IngestionCleanupMapper ingestionCleanupMapper, AppProperties appProperties,
            TransactionTemplate transactionTemplate) {
        this.ingestionCleanupMapper = ingestionCleanupMapper;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 清理当前保留周期之前的接收记录；已有任务运行时立即跳过。
     *
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredIngestions() {
        LocalDateTime cutoffTime = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getMetrics().getIngestionRetentionDays());
        return cleanupExpiredIngestions(cutoffTime);
    }

    /**
     * 按指定边界执行清理，供独立数据库验收固定 cutoff 边界。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredIngestions(LocalDateTime cutoffTime) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }

        long startedAt = System.nanoTime();
        int batchCount = 0;
        int deletedRows = 0;
        try {
            int maxBatches = appProperties.getMetrics().getIngestionCleanupMaxBatchesPerRun();
            int batchSize = appProperties.getMetrics().getIngestionCleanupBatchSize();
            while (batchCount < maxBatches) {
                Integer deleted = transactionTemplate.execute(status ->
                        ingestionCleanupMapper.deleteExpiredBatch(cutoffTime, batchSize));
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

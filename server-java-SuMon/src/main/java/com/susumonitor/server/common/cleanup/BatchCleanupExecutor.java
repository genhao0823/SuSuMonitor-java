package com.susumonitor.server.common.cleanup;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 按保留期批量删旧行的共享执行器：统一承担同任务防重入、分批循环、
 * 每批独立事务与耗时统计，各清理服务只需提供过期边界与单批删除操作。
 *
 * <p>防重入按任务名隔离：同名任务重叠触发时后者立即跳过（返回空），
 * 不同任务互不影响。删除 SQL 与保留期/批量参数由调用方决定。</p>
 */
@Component
public class BatchCleanupExecutor {

    /** 单批删除操作：按过期边界删除至多 batchSize 行，返回实际删除行数。 */
    @FunctionalInterface
    public interface BatchDeleteOperation {

        /**
         * 执行一批删除。
         *
         * @param cutoffTime 过期边界，严格早于该时间才删除
         * @param batchSize 单批最大删除行数
         * @return 实际删除行数
         */
        int deleteBatch(LocalDateTime cutoffTime, int batchSize);
    }

    private final TransactionTemplate transactionTemplate;
    private final ConcurrentMap<String, AtomicBoolean> runningJobs = new ConcurrentHashMap<>();

    /** 注入每批独立事务模板。 */
    public BatchCleanupExecutor(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 执行一轮分批清理，直到删空或达到批次上限。
     *
     * @param jobName 任务名（防重入维度，同名任务重叠触发时后者跳过）
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @param operation 单批删除操作（通常为 Mapper 方法引用）
     * @param batchSize 单批最大删除行数
     * @param maxBatches 单轮最大批次数
     * @return 实际执行时返回统计结果，同名任务已在运行时返回空
     */
    public Optional<CleanupResult> run(String jobName, LocalDateTime cutoffTime,
            BatchDeleteOperation operation, int batchSize, int maxBatches) {
        AtomicBoolean running = runningJobs.computeIfAbsent(jobName, ignored -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        long startedAt = System.nanoTime();
        int batchCount = 0;
        int deletedRows = 0;
        try {
            while (batchCount < maxBatches) {
                Integer deleted = transactionTemplate.execute(status ->
                        operation.deleteBatch(cutoffTime, batchSize));
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

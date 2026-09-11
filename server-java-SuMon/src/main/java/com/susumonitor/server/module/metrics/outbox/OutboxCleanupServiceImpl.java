package com.susumonitor.server.module.metrics.outbox;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 分批清理超过保留期的已发布 Outbox 记录；防重入与批量循环由共享执行器承担。
 */
@Service
@ConditionalOnProperty(name = "susumonitor.rabbitmq.outbox-cleanup-enabled", havingValue = "true")
public class OutboxCleanupServiceImpl implements OutboxCleanupService {

    private final OutboxCleanupMapper outboxCleanupMapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /**
     * 构造 Outbox 清理服务。
     *
     * @param outboxCleanupMapper Outbox 清理 Mapper
     * @param appProperties 应用配置
     * @param batchCleanupExecutor 共享批量清理执行器
     */
    public OutboxCleanupServiceImpl(OutboxCleanupMapper outboxCleanupMapper, AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.outboxCleanupMapper = outboxCleanupMapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /**
     * 清理当前保留周期之前的已发布 Outbox 记录；已有任务运行时立即跳过。
     *
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredPublishedOutbox() {
        LocalDateTime cutoffTime = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getRabbitmq().getOutboxRetentionDays());
        return cleanupExpiredPublishedOutbox(cutoffTime);
    }

    /**
     * 按指定边界执行清理，供独立数据库验收固定 cutoff 边界。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredPublishedOutbox(LocalDateTime cutoffTime) {
        return batchCleanupExecutor.run("outbox", cutoffTime,
                outboxCleanupMapper::deletePublishedBeforeBatch,
                appProperties.getRabbitmq().getOutboxCleanupBatchSize(),
                appProperties.getRabbitmq().getOutboxCleanupMaxBatchesPerRun());
    }

}

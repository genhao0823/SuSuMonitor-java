package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.metrics.mapper.MetricsCleanupMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 分批清理 Metrics 过期数据；防重入与批量循环由共享执行器承担。
 */
@Slf4j
@Service
public class MetricsCleanupServiceImpl implements MetricsCleanupService {

    private final MetricsCleanupMapper metricsCleanupMapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /**
     * 构造 Metrics 清理服务。
     *
     * @param metricsCleanupMapper Metrics 清理 Mapper
     * @param appProperties 应用配置
     * @param batchCleanupExecutor 共享批量清理执行器
     */
    public MetricsCleanupServiceImpl(
            MetricsCleanupMapper metricsCleanupMapper,
            AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.metricsCleanupMapper = metricsCleanupMapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /**
     * 清理当前保留周期之前的 Metrics 数据；已有任务运行时立即跳过。
     *
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    public Optional<CleanupResult> cleanupExpiredMetrics() {
        LocalDateTime cutoffTime = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getMetrics().getRetentionDays());
        return cleanupExpiredMetrics(cutoffTime);
    }

    /**
     * 按指定边界执行清理，供独立数据库验收固定 cutoff 边界。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    public Optional<CleanupResult> cleanupExpiredMetrics(LocalDateTime cutoffTime) {
        return batchCleanupExecutor.run("metrics", cutoffTime, metricsCleanupMapper::deleteExpiredBatch,
                appProperties.getMetrics().getCleanupBatchSize(),
                appProperties.getMetrics().getCleanupMaxBatchesPerRun());
    }

}

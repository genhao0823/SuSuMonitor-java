package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.metrics.mapper.IngestionCleanupMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 分批清理指标幂等接收记录（metrics_ingestions）；防重入与批量循环由共享执行器承担。
 */
@Slf4j
@Service
public class IngestionCleanupServiceImpl implements IngestionCleanupService {

    private final IngestionCleanupMapper ingestionCleanupMapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /**
     * 构造指标幂等接收记录清理服务。
     *
     * @param ingestionCleanupMapper 接收记录清理 Mapper
     * @param appProperties 应用配置
     * @param batchCleanupExecutor 共享批量清理执行器
     */
    public IngestionCleanupServiceImpl(IngestionCleanupMapper ingestionCleanupMapper, AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.ingestionCleanupMapper = ingestionCleanupMapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
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
        return batchCleanupExecutor.run("ingestions", cutoffTime, ingestionCleanupMapper::deleteExpiredBatch,
                appProperties.getMetrics().getIngestionCleanupBatchSize(),
                appProperties.getMetrics().getIngestionCleanupMaxBatchesPerRun());
    }

}

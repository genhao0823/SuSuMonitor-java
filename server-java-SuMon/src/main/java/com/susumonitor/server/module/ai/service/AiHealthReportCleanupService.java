package com.susumonitor.server.module.ai.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.mapper.AiHealthReportMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 按保留期分批清理过期的 AI 定时健康报告；防重入与批量循环由共享执行器承担。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.report.enabled", havingValue = "true")
public class AiHealthReportCleanupService {
    private final AiHealthReportMapper mapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /** 注入报告 Mapper、配置和共享批量清理执行器。 */
    public AiHealthReportCleanupService(AiHealthReportMapper mapper, AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /** 按当前配置清理过期报告。 */
    public Optional<CleanupResult> cleanupExpired() {
        AppProperties.Ai.Report config = appProperties.getAi().getReport();
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(config.getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理，便于数据库验收和边界测试。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        AppProperties.Ai.Report config = appProperties.getAi().getReport();
        return batchCleanupExecutor.run("ai-health-report", cutoff, mapper::deleteExpiredBatch,
                config.getAuditCleanupBatchSize(), config.getAuditCleanupMaxBatchesPerRun());
    }
}

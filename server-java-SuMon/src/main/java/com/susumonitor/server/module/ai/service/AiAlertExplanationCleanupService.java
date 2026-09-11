package com.susumonitor.server.module.ai.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.mapper.AiAlertExplanationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 按保留期分批清理过期的 AI 告警解释存储；防重入与批量循环由共享执行器承担。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public class AiAlertExplanationCleanupService {
    private final AiAlertExplanationMapper mapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /** 注入解释 Mapper、配置和共享批量清理执行器。 */
    public AiAlertExplanationCleanupService(AiAlertExplanationMapper mapper, AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /** 按当前配置清理过期解释记录。 */
    public Optional<CleanupResult> cleanupExpired() {
        AppProperties.Ai.Explanation config = appProperties.getAi().getExplanation();
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(config.getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理，便于数据库验收和边界测试。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        AppProperties.Ai.Explanation config = appProperties.getAi().getExplanation();
        return batchCleanupExecutor.run("ai-alert-explanation", cutoff, mapper::deleteExpiredBatch,
                config.getAuditCleanupBatchSize(), config.getAuditCleanupMaxBatchesPerRun());
    }
}

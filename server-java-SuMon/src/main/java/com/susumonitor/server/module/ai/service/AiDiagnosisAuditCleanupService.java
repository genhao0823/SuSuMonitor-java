package com.susumonitor.server.module.ai.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.mapper.AiDiagnosticRunMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 按保留期分批清理 AI 诊断审计记录；防重入与批量循环由共享执行器承担。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiDiagnosisAuditCleanupService {
    private final AiDiagnosticRunMapper mapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /** 注入审计 Mapper、配置和共享批量清理执行器。 */
    public AiDiagnosisAuditCleanupService(AiDiagnosticRunMapper mapper, AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /** 按当前配置清理过期审计记录。 */
    public Optional<CleanupResult> cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getAi().getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理，便于数据库验收和边界测试。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        return batchCleanupExecutor.run("ai-diagnosis-audit", cutoff, mapper::deleteExpiredBatch,
                appProperties.getAi().getAuditCleanupBatchSize(),
                appProperties.getAi().getAuditCleanupMaxBatchesPerRun());
    }
}

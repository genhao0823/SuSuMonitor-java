package com.susumonitor.server.module.ai.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.mapper.AiQaRunMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 按保留期分批清理 AI 运维问答审计记录；防重入与批量循环由共享执行器承担。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiQaAuditCleanupService {
    private final AiQaRunMapper mapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /** 注入审计 Mapper、配置和共享批量清理执行器。 */
    public AiQaAuditCleanupService(AiQaRunMapper mapper, AppProperties appProperties,
            BatchCleanupExecutor batchCleanupExecutor) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /** 按当前配置清理过期问答审计记录；qa 清理开关关闭时跳过。 */
    public Optional<CleanupResult> cleanupExpired() {
        AppProperties.Ai.Qa qa = appProperties.getAi().getQa();
        if (!qa.isAuditCleanupEnabled()) {
            return Optional.empty();
        }
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(qa.getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理，便于数据库验收和边界测试。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        AppProperties.Ai.Qa qa = appProperties.getAi().getQa();
        return batchCleanupExecutor.run("ai-qa-audit", cutoff, mapper::deleteExpiredBatch,
                qa.getAuditCleanupBatchSize(), qa.getAuditCleanupMaxBatchesPerRun());
    }
}

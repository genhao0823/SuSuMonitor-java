package com.susumonitor.server.module.ai.service;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.mapper.AiDiagnosticRunMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 按保留期分批清理 AI 诊断审计记录，避免审计表无限增长。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiDiagnosisAuditCleanupService {
    private final AiDiagnosticRunMapper mapper;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 注入审计 Mapper、配置和每批事务模板。 */
    public AiDiagnosisAuditCleanupService(AiDiagnosticRunMapper mapper, AppProperties appProperties,
            TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /** 按当前配置清理过期审计记录。 */
    public Optional<CleanupResult> cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getAi().getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理，便于数据库验收和边界测试。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        long started = System.nanoTime();
        int batches = 0;
        int rows = 0;
        try {
            while (batches < appProperties.getAi().getAuditCleanupMaxBatchesPerRun()) {
                Integer deleted = transactionTemplate.execute(status -> mapper.deleteExpiredBatch(cutoff,
                        appProperties.getAi().getAuditCleanupBatchSize()));
                int current = deleted == null ? 0 : deleted;
                if (current == 0) break;
                batches++;
                rows += current;
            }
            return Optional.of(new CleanupResult(cutoff, batches, rows,
                    Duration.ofNanos(System.nanoTime() - started).toMillis()));
        } finally {
            running.set(false);
        }
    }

    /** 一轮清理的统计结果。 */
    public record CleanupResult(LocalDateTime cutoffTime, int batchCount, int deletedRows, long durationMs) { }
}

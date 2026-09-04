package com.susumonitor.server.module.ai.service;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.mapper.AiAlertExplanationMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 按保留期分批清理过期的 AI 告警解释存储，避免解释表无限增长。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public class AiAlertExplanationCleanupService {
    private final AiAlertExplanationMapper mapper;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 注入解释 Mapper、配置和每批事务模板。 */
    public AiAlertExplanationCleanupService(AiAlertExplanationMapper mapper, AppProperties appProperties,
            TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /** 按当前配置清理过期解释记录。 */
    public Optional<CleanupResult> cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getAi().getExplanation().getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理，便于数据库验收和边界测试。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        long started = System.nanoTime();
        int batches = 0;
        int rows = 0;
        try {
            AppProperties.Ai.Explanation config = appProperties.getAi().getExplanation();
            while (batches < config.getAuditCleanupMaxBatchesPerRun()) {
                Integer deleted = transactionTemplate.execute(status ->
                        mapper.deleteExpiredBatch(cutoff, config.getAuditCleanupBatchSize()));
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

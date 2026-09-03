package com.susumonitor.server.module.command;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.command.mapper.CommandRunMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 命令域审计保留期分批清理：删除超过保留期的 ai_command_runs 行。
 *
 * <p>同 JVM AtomicBoolean 防重入；每批独立事务，与既有清理服务同模式。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandAuditCleanupService {

    private final CommandRunMapper mapper;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 注入审计 Mapper、配置与事务模板。 */
    public CommandAuditCleanupService(CommandRunMapper mapper, AppProperties appProperties,
            TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /** 按当前保留期执行一轮清理；重叠触发返回空。 */
    public Optional<CleanupResult> cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getAi().getCommand().getAuditRetentionDays());
        return cleanupExpired(cutoff);
    }

    /** 按固定时间边界清理（验收与测试用）。 */
    public Optional<CleanupResult> cleanupExpired(LocalDateTime cutoff) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        long started = System.nanoTime();
        int batches = 0;
        int rows = 0;
        try {
            var commandProps = appProperties.getAi().getCommand();
            while (batches < commandProps.getAuditCleanupMaxBatchesPerRun()) {
                Integer deleted = transactionTemplate.execute(status ->
                        mapper.deleteExpiredBatch(cutoff, commandProps.getAuditCleanupBatchSize()));
                int current = deleted == null ? 0 : deleted;
                if (current == 0) {
                    break;
                }
                batches++;
                rows += current;
            }
            return Optional.of(new CleanupResult(cutoff, batches, rows,
                    Duration.ofNanos(System.nanoTime() - started).toMillis()));
        } finally {
            running.set(false);
        }
    }

    /** 一轮清理统计。 */
    public record CleanupResult(LocalDateTime cutoffTime, int batchCount, int deletedRows, long durationMs) {
    }
}

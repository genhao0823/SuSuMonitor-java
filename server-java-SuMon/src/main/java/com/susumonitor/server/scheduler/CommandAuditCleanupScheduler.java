package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.command.CommandAuditCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 命令域审计保留期清理调度。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandAuditCleanupScheduler {

    private final CommandAuditCleanupService cleanupService;

    /** 构造清理调度器。 */
    public CommandAuditCleanupScheduler(CommandAuditCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** 按配置 cron 执行一次过期审计清理；异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.ai.command.audit-cleanup-cron}")
    public void cleanupExpiredCommandRuns() {
        try {
            cleanupService.cleanupExpired().ifPresentOrElse(
                    result -> log.info("command audit cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("command audit cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("command audit cleanup failed", exception);
        }
    }
}

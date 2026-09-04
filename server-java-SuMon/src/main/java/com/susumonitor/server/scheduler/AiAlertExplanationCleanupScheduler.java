package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.ai.service.AiAlertExplanationCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按配置定时清理 AI 告警解释存储，不直接访问数据库 Mapper。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public class AiAlertExplanationCleanupScheduler {
    private final AiAlertExplanationCleanupService cleanupService;

    /** 构造 AI 解释清理调度器。 */
    public AiAlertExplanationCleanupScheduler(AiAlertExplanationCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** 执行一次过期解释清理，异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.ai.explanation.audit-cleanup-cron}")
    public void cleanupExpiredAiAlertExplanations() {
        try {
            cleanupService.cleanupExpired().ifPresentOrElse(
                    result -> log.info("AI alert explanation cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("AI alert explanation cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("AI alert explanation cleanup failed", exception);
        }
    }
}

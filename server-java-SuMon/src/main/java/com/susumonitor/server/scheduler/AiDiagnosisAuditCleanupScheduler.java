package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.ai.service.AiDiagnosisAuditCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按配置定时清理 AI 诊断审计记录，不直接访问数据库 Mapper。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiDiagnosisAuditCleanupScheduler {
    private final AiDiagnosisAuditCleanupService cleanupService;

    /** 构造 AI 审计清理调度器。 */
    public AiDiagnosisAuditCleanupScheduler(AiDiagnosisAuditCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** 执行一次过期审计清理，异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.ai.audit-cleanup-cron}")
    public void cleanupExpiredAiAudits() {
        try {
            cleanupService.cleanupExpired().ifPresentOrElse(
                    result -> log.info("AI audit cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("AI audit cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("AI audit cleanup failed", exception);
        }
    }
}

package com.susumonitor.server.scheduler;

import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.module.ai.service.AiAlertExplanationCleanupService;
import com.susumonitor.server.module.ai.service.AiDiagnosisAuditCleanupService;
import com.susumonitor.server.module.ai.service.AiHealthReportCleanupService;
import com.susumonitor.server.module.ai.service.AiQaAuditCleanupService;
import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupService;
import com.susumonitor.server.module.alert.service.AlertNotificationCleanupService;
import com.susumonitor.server.module.alert.service.AlertRecordCleanupService;
import com.susumonitor.server.module.command.CommandAuditCleanupService;
import com.susumonitor.server.module.metrics.outbox.OutboxCleanupService;
import com.susumonitor.server.module.metrics.service.IngestionCleanupService;
import com.susumonitor.server.module.metrics.service.MetricsCleanupService;
import com.susumonitor.server.module.server.service.SshTestHistoryCleanupService;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据保留期清理的统一调度入口：每项清理一个 @Scheduled 方法，
 * 触发对应清理服务并记录结果，不直接访问数据库 Mapper。
 *
 * <p>清理服务按模块/开关条件装配，这里经 {@link ObjectProvider} 懒获取：
 * 对应 Bean 未装配（模块关闭或清理开关关闭）时本轮直接跳过，
 * 与分散调度器时代的 @ConditionalOnProperty 语义一致。防重入、
 * 分批与事务由共享 {@code BatchCleanupExecutor} 承担。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataCleanupSchedules {

    private final ObjectProvider<MetricsCleanupService> metricsCleanup;
    private final ObjectProvider<IngestionCleanupService> ingestionCleanup;
    private final ObjectProvider<AlertRecordCleanupService> alertRecordCleanup;
    private final ObjectProvider<AlertNotificationCleanupService> alertNotificationCleanup;
    private final ObjectProvider<ConsumeRecordCleanupService> consumeRecordCleanup;
    private final ObjectProvider<OutboxCleanupService> outboxCleanup;
    private final ObjectProvider<SshTestHistoryCleanupService> sshTestHistoryCleanup;
    private final ObjectProvider<AiDiagnosisAuditCleanupService> aiDiagnosisAuditCleanup;
    private final ObjectProvider<AiAlertExplanationCleanupService> aiAlertExplanationCleanup;
    private final ObjectProvider<AiQaAuditCleanupService> aiQaAuditCleanup;
    private final ObjectProvider<CommandAuditCleanupService> commandAuditCleanup;
    private final ObjectProvider<AiHealthReportCleanupService> aiHealthReportCleanup;

    /** 触发 Metrics 过期清理。 */
    @Scheduled(cron = "${susumonitor.metrics.cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredMetrics() {
        MetricsCleanupService service = metricsCleanup.getIfAvailable();
        if (service != null) {
            run("Metrics cleanup", service::cleanupExpiredMetrics);
        }
    }

    /** 触发指标幂等接收记录过期清理。 */
    @Scheduled(cron = "${susumonitor.metrics.ingestion-cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredIngestions() {
        IngestionCleanupService service = ingestionCleanup.getIfAvailable();
        if (service != null) {
            run("Ingestion cleanup", service::cleanupExpiredIngestions);
        }
    }

    /** 触发告警记录过期清理。 */
    @Scheduled(cron = "${susumonitor.alert.record-cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredAlertRecords() {
        AlertRecordCleanupService service = alertRecordCleanup.getIfAvailable();
        if (service != null) {
            run("Alert record cleanup", service::cleanupExpiredAlertRecords);
        }
    }

    /** 触发通知投递记录过期清理。 */
    @Scheduled(cron = "${susumonitor.alert.notification-cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredNotifications() {
        AlertNotificationCleanupService service = alertNotificationCleanup.getIfAvailable();
        if (service != null) {
            run("Alert notification cleanup", service::cleanupExpiredNotifications);
        }
    }

    /** 触发消费幂等记录过期清理。 */
    @Scheduled(cron = "${susumonitor.rabbitmq.consume-record-cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredConsumeRecords() {
        ConsumeRecordCleanupService service = consumeRecordCleanup.getIfAvailable();
        if (service != null) {
            run("Consume record cleanup", service::cleanupExpiredConsumeRecords);
        }
    }

    /** 触发已发布 Outbox 记录过期清理。 */
    @Scheduled(cron = "${susumonitor.rabbitmq.outbox-cleanup-cron:0 30 3 * * ?}")
    public void cleanupExpiredPublishedOutbox() {
        OutboxCleanupService service = outboxCleanup.getIfAvailable();
        if (service != null) {
            run("Outbox cleanup", service::cleanupExpiredPublishedOutbox);
        }
    }

    /** 触发 SSH 测试历史过期清理。 */
    @Scheduled(cron = "${susumonitor.ssh-test-history.cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredSshTestHistory() {
        SshTestHistoryCleanupService service = sshTestHistoryCleanup.getIfAvailable();
        if (service != null) {
            run("SSH test history cleanup", service::cleanupExpiredSshTestHistory);
        }
    }

    /** 触发 AI 诊断审计过期清理。 */
    @Scheduled(cron = "${susumonitor.ai.audit-cleanup-cron:0 30 3 * * ?}")
    public void cleanupExpiredAiAudits() {
        AiDiagnosisAuditCleanupService service = aiDiagnosisAuditCleanup.getIfAvailable();
        if (service != null) {
            run("AI audit cleanup", service::cleanupExpired);
        }
    }

    /** 触发 AI 告警解释过期清理。 */
    @Scheduled(cron = "${susumonitor.ai.explanation.audit-cleanup-cron:0 0 4 * * ?}")
    public void cleanupExpiredAiAlertExplanations() {
        AiAlertExplanationCleanupService service = aiAlertExplanationCleanup.getIfAvailable();
        if (service != null) {
            run("AI alert explanation cleanup", service::cleanupExpired);
        }
    }

    /** 触发 AI 问答审计过期清理；空结果同时覆盖开关关闭与重叠触发两种情况。 */
    @Scheduled(cron = "${susumonitor.ai.qa.audit-cleanup-cron:0 15 4 * * ?}")
    public void cleanupExpiredAiQaAudits() {
        AiQaAuditCleanupService service = aiQaAuditCleanup.getIfAvailable();
        if (service != null) {
            try {
                service.cleanupExpired().ifPresentOrElse(
                        result -> log.info("AI QA audit cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                                result.cutoffTime(), result.batchCount(), result.deletedRows()),
                        () -> log.debug("AI QA audit cleanup skipped (disabled or another run is active)"));
            } catch (RuntimeException exception) {
                log.error("AI QA audit cleanup failed", exception);
            }
        }
    }

    /** 触发命令域审计过期清理。 */
    @Scheduled(cron = "${susumonitor.ai.command.audit-cleanup-cron:0 45 3 * * ?}")
    public void cleanupExpiredCommandRuns() {
        CommandAuditCleanupService service = commandAuditCleanup.getIfAvailable();
        if (service != null) {
            run("command audit cleanup", service::cleanupExpired);
        }
    }

    /** 触发 AI 定时健康报告过期清理。 */
    @Scheduled(cron = "${susumonitor.ai.report.audit-cleanup-cron:0 30 4 * * ?}")
    public void cleanupExpiredAiHealthReports() {
        AiHealthReportCleanupService service = aiHealthReportCleanup.getIfAvailable();
        if (service != null) {
            run("AI health report cleanup", service::cleanupExpired);
        }
    }

    /** 执行一项清理并记录结果；异常只影响当前轮次。 */
    private void run(String jobLabel, Supplier<Optional<CleanupResult>> job) {
        try {
            job.get().ifPresentOrElse(
                    result -> log.info("{} completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            jobLabel, result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("{} skipped because another run is active", jobLabel));
        } catch (RuntimeException exception) {
            log.error("{} failed", jobLabel, exception);
        }
    }
}

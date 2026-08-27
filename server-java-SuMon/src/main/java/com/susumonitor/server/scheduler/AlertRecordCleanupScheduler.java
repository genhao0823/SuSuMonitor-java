package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.alert.service.AlertRecordCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按配置定时触发告警记录清理，不直接访问数据库 Mapper。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.alert.record-cleanup-enabled", havingValue = "true")
public class AlertRecordCleanupScheduler {

    private final AlertRecordCleanupService alertRecordCleanupService;

    /**
     * 构造告警记录清理调度器。
     *
     * @param alertRecordCleanupService 告警记录清理服务
     */
    public AlertRecordCleanupScheduler(AlertRecordCleanupService alertRecordCleanupService) {
        this.alertRecordCleanupService = alertRecordCleanupService;
    }

    /** 执行一次过期告警记录清理；异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.alert.record-cleanup-cron}")
    public void cleanupExpiredAlertRecords() {
        try {
            alertRecordCleanupService.cleanupExpiredAlertRecords().ifPresentOrElse(
                    result -> log.info("Alert record cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("Alert record cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("Alert record cleanup failed", exception);
        }
    }
}

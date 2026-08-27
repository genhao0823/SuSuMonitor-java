package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.alert.service.AlertNotificationCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按配置定时触发通知投递记录清理，不直接访问数据库 Mapper。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.alert.notification-cleanup-enabled", havingValue = "true")
public class AlertNotificationCleanupScheduler {

    private final AlertNotificationCleanupService notificationCleanupService;

    /**
     * 构造通知投递清理调度器。
     *
     * @param notificationCleanupService 通知投递清理服务
     */
    public AlertNotificationCleanupScheduler(AlertNotificationCleanupService notificationCleanupService) {
        this.notificationCleanupService = notificationCleanupService;
    }

    /** 执行一次过期通知投递清理；异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.alert.notification-cleanup-cron}")
    public void cleanupExpiredNotifications() {
        try {
            notificationCleanupService.cleanupExpiredNotifications().ifPresentOrElse(
                    result -> log.info("Alert notification cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("Alert notification cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("Alert notification cleanup failed", exception);
        }
    }
}

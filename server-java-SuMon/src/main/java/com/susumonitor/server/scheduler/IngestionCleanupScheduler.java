package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.metrics.service.IngestionCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按配置定时触发指标幂等接收记录清理，不直接访问数据库 Mapper。
 */
@Slf4j
@Component
public class IngestionCleanupScheduler {

    private final IngestionCleanupService ingestionCleanupService;

    /**
     * 构造指标幂等接收记录清理调度器。
     *
     * @param ingestionCleanupService 接收记录清理服务
     */
    public IngestionCleanupScheduler(IngestionCleanupService ingestionCleanupService) {
        this.ingestionCleanupService = ingestionCleanupService;
    }

    /**
     * 执行一次过期接收记录清理；异常只影响当前轮次。
     */
    @Scheduled(cron = "${susumonitor.metrics.ingestion-cleanup-cron}")
    public void cleanupExpiredIngestions() {
        try {
            ingestionCleanupService.cleanupExpiredIngestions().ifPresentOrElse(
                    result -> log.info(
                            "Ingestion cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("Ingestion cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("Ingestion cleanup failed", exception);
        }
    }
}

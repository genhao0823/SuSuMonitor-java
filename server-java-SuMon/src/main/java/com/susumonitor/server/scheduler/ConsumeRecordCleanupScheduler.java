package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.alert.consume.ConsumeRecordCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按配置定时触发消费幂等记录清理，不直接访问数据库 Mapper。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.consume-record-cleanup-enabled", havingValue = "true")
public class ConsumeRecordCleanupScheduler {

    private final ConsumeRecordCleanupService consumeRecordCleanupService;

    /**
     * 构造消费幂等记录清理调度器。
     *
     * @param consumeRecordCleanupService 消费记录清理服务
     */
    public ConsumeRecordCleanupScheduler(ConsumeRecordCleanupService consumeRecordCleanupService) {
        this.consumeRecordCleanupService = consumeRecordCleanupService;
    }

    /** 执行一次过期消费记录清理；异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.rabbitmq.consume-record-cleanup-cron}")
    public void cleanupExpiredConsumeRecords() {
        try {
            consumeRecordCleanupService.cleanupExpiredConsumeRecords().ifPresentOrElse(
                    result -> log.info("Consume record cleanup completed, cutoffTime={}, batchCount={}, deletedRows={}",
                            result.cutoffTime(), result.batchCount(), result.deletedRows()),
                    () -> log.info("Consume record cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("Consume record cleanup failed", exception);
        }
    }
}

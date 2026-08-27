package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.server.service.SshTestHistoryCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按配置定时触发 SSH 测试历史清理，不直接访问数据库 Mapper。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ssh-test-history.cleanup-enabled", havingValue = "true")
public class SshTestHistoryCleanupScheduler {

    private final SshTestHistoryCleanupService sshTestHistoryCleanupService;

    /**
     * 构造 SSH 测试历史清理调度器。
     *
     * @param sshTestHistoryCleanupService SSH 测试历史清理服务
     */
    public SshTestHistoryCleanupScheduler(SshTestHistoryCleanupService sshTestHistoryCleanupService) {
        this.sshTestHistoryCleanupService = sshTestHistoryCleanupService;
    }

    /** 执行一次过期 SSH 测试历史清理；异常只影响当前轮次。 */
    @Scheduled(cron = "${susumonitor.ssh-test-history.cleanup-cron}")
    public void cleanupExpiredSshTestHistory() {
        try {
            sshTestHistoryCleanupService.cleanupExpiredSshTestHistory().ifPresentOrElse(
                    result -> log.info("SSH test history cleanup completed, cutoffTime={}, batchCount={}, "
                                    + "deletedRows={}", result.cutoffTime(), result.batchCount(),
                            result.deletedRows()),
                    () -> log.info("SSH test history cleanup skipped because another run is active"));
        } catch (RuntimeException exception) {
            log.error("SSH test history cleanup failed", exception);
        }
    }
}

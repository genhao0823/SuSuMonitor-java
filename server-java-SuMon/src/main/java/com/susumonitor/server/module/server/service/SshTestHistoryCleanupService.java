package com.susumonitor.server.module.server.service;

import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 定义 SSH 测试历史保留期清理的调度业务契约。
 */
public interface SshTestHistoryCleanupService {

    /** 按配置的保留周期清理过期 SSH 测试历史。 */
    Optional<CleanupResult> cleanupExpiredSshTestHistory();

    /** 按指定时间边界清理过期 SSH 测试历史，供验证场景固定边界。 */
    Optional<CleanupResult> cleanupExpiredSshTestHistory(LocalDateTime cutoffTime);
}

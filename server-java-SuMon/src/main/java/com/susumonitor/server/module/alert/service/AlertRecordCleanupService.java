package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 定义告警记录保留期清理的调度业务契约。
 */
public interface AlertRecordCleanupService {

    /** 按配置的保留周期清理过期告警记录。 */
    Optional<CleanupResult> cleanupExpiredAlertRecords();

    /** 按指定时间边界清理过期告警记录，供验证场景固定边界。 */
    Optional<CleanupResult> cleanupExpiredAlertRecords(LocalDateTime cutoffTime);
}

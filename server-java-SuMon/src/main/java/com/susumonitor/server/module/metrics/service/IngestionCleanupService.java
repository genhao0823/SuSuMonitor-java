package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 定义指标幂等接收记录保留期清理的调度业务契约。
 */
public interface IngestionCleanupService {

    /** 按配置的保留周期清理过期接收记录。 */
    Optional<CleanupResult> cleanupExpiredIngestions();

    /** 按指定时间边界清理过期接收记录，供验证场景固定边界。 */
    Optional<CleanupResult> cleanupExpiredIngestions(LocalDateTime cutoffTime);
}

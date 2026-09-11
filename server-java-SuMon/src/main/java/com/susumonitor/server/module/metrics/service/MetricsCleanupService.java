package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.cleanup.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 定义指标保留数据清理的调度业务契约。
 */
public interface MetricsCleanupService {

    /** 按配置的保留周期清理过期指标。 */
    Optional<CleanupResult> cleanupExpiredMetrics();

    /** 按指定时间边界清理过期指标，供验证场景固定边界。 */
    Optional<CleanupResult> cleanupExpiredMetrics(LocalDateTime cutoffTime);
}

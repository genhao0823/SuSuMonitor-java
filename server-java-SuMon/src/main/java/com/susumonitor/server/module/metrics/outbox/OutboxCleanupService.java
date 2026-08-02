package com.susumonitor.server.module.metrics.outbox;

import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 定义已发布 Outbox 记录保留期清理的调度业务契约。
 */
public interface OutboxCleanupService {

    /** 按配置保留期清理已确认发布的 Outbox 记录。 */
    Optional<CleanupResult> cleanupExpiredPublishedOutbox();

    /** 按指定时间边界清理，供边界验证场景使用。 */
    Optional<CleanupResult> cleanupExpiredPublishedOutbox(LocalDateTime cutoffTime);
}

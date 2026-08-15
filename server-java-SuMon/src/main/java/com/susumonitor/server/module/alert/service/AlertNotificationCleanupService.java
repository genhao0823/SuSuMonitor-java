package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.module.metrics.service.MetricsCleanupService.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 清理超过保留期的通知投递记录（alert_notifications）。
 */
public interface AlertNotificationCleanupService {

    /**
     * 清理当前保留周期之前的通知投递记录；已有任务运行时立即跳过。
     *
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    Optional<CleanupResult> cleanupExpiredNotifications();

    /**
     * 按指定边界执行清理，供独立数据库验收固定 cutoff 边界。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    Optional<CleanupResult> cleanupExpiredNotifications(LocalDateTime cutoffTime);
}

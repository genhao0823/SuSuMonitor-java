package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.common.cleanup.BatchCleanupExecutor;
import com.susumonitor.server.common.cleanup.CleanupResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.mapper.AlertNotificationCleanupMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 分批清理超过保留期的通知投递记录（alert_notifications）；防重入与批量循环由共享执行器承担。
 *
 * <p>补齐 V21 通知表"只增不删"的增长风险：清理按 created_at 分批删除，
 * 保留期、批大小与轮次上限均可配置，语义与其他清理服务（记录/Outbox/消费记录）一致。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.alert.notification-cleanup-enabled", havingValue = "true")
public class AlertNotificationCleanupServiceImpl implements AlertNotificationCleanupService {

    private final AlertNotificationCleanupMapper notificationCleanupMapper;
    private final AppProperties appProperties;
    private final BatchCleanupExecutor batchCleanupExecutor;

    /**
     * 构造通知投递清理服务。
     *
     * @param notificationCleanupMapper 通知投递清理 Mapper
     * @param appProperties 应用配置
     * @param batchCleanupExecutor 共享批量清理执行器
     */
    public AlertNotificationCleanupServiceImpl(AlertNotificationCleanupMapper notificationCleanupMapper,
            AppProperties appProperties, BatchCleanupExecutor batchCleanupExecutor) {
        this.notificationCleanupMapper = notificationCleanupMapper;
        this.appProperties = appProperties;
        this.batchCleanupExecutor = batchCleanupExecutor;
    }

    /**
     * 清理当前保留周期之前的通知投递记录；已有任务运行时立即跳过。
     *
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredNotifications() {
        LocalDateTime cutoffTime = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(appProperties.getAlert().getNotificationRetentionDays());
        return cleanupExpiredNotifications(cutoffTime);
    }

    /**
     * 按指定边界执行清理，供独立数据库验收固定 cutoff 边界。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @return 实际执行时返回结果，重叠触发时返回空
     */
    @Override
    public Optional<CleanupResult> cleanupExpiredNotifications(LocalDateTime cutoffTime) {
        return batchCleanupExecutor.run("alert-notifications", cutoffTime,
                notificationCleanupMapper::deleteExpiredBatch,
                appProperties.getAlert().getNotificationCleanupBatchSize(),
                appProperties.getAlert().getNotificationCleanupMaxBatchesPerRun());
    }

}

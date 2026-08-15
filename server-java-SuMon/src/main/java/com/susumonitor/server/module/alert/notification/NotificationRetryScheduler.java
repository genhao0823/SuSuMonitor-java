package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时重试到期未送达的告警通知（V21 退避重试）。
 *
 * <p>每 30 秒扫描 alert_notifications 中 status=pending 且到期的行，
 * 重新尝试发送。规则被删除/禁用或记录缺失时直接置 failed 不再重试。
 * 单次最多处理 50 条，避免瞬时大量失败拖慢调度线程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetryScheduler {

    /** 单轮最多重试的通知数。 */
    static final int RETRY_BATCH_LIMIT = 50;

    private final AlertNotificationMapper notificationMapper;
    private final AlertRecordMapper recordMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertNotificationService notificationService;
    private final Clock clock;

    /** 定时扫描到期 pending 通知并执行重试。 */
    @Scheduled(fixedDelay = 30_000)
    public void retryPendingNotifications() {
        List<AlertNotificationEntity> pending = notificationMapper
                .selectPendingForRetry(LocalDateTime.now(clock), RETRY_BATCH_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        for (AlertNotificationEntity notification : pending) {
            retryOne(notification);
        }
    }

    /** 重试单条通知，规则或记录不可用时直接置 failed。 */
    private void retryOne(AlertNotificationEntity notification) {
        AlertRecordEntity record = recordMapper.selectRecordById(notification.getAlertRecordId());
        if (record == null) {
            notificationMapper.markAttempt(notification.getId(), "failed",
                    notification.getAttempts(), null, "alert record not found");
            return;
        }
        AlertRuleEntity rule = ruleMapper.selectActiveRuleById(record.getRuleId());
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            notificationMapper.markAttempt(notification.getId(), "failed",
                    notification.getAttempts(), null, "rule unavailable or disabled");
            return;
        }
        notificationService.retry(notification, rule, toRecordVo(record));
    }

    /** 将 Entity 转换为 VO，时间字段转为 UTC OffsetDateTime。 */
    private AlertRecordVo toRecordVo(AlertRecordEntity entity) {
        AlertRecordVo vo = new AlertRecordVo();
        vo.setId(entity.getId());
        vo.setRuleId(entity.getRuleId());
        vo.setServerId(entity.getServerId());
        vo.setMetric(entity.getMetric());
        vo.setCurrentValue(entity.getCurrentValue());
        vo.setThresholdValue(entity.getThresholdValue());
        vo.setLevel(entity.getLevel());
        vo.setStatus(entity.getStatus());
        vo.setMessage(entity.getMessage());
        vo.setTriggeredAt(AlertRecordVo.toOffset(entity.getTriggeredAt()));
        vo.setResolvedAt(AlertRecordVo.toOffset(entity.getResolvedAt()));
        return vo;
    }
}

package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.util.List;

/**
 * 告警外部通知服务契约。
 *
 * <p>按规则上配置的通知渠道（邮件/钉钉/自定义 Webhook）发送通知，
 * 每个渠道在 alert_notifications 表记一行（V21）并支持退避重试；
 * 通知失败不影响告警记录本身。</p>
 *
 * <p>通知触发支持两条路径：消息驱动的消费者在消费事务内
 * {@link #scheduleNotifications} 排程（同事务插 pending 行），事务提交后
 * {@link #sendScheduled} 异步发送；{@link #notify} 为兼容旧调用的组合入口。</p>
 */
public interface AlertNotificationService {

    /** 按规则配置的渠道首次异步发送通知（兼容入口：排程 + 发送）。 */
    void notify(AlertRuleEntity rule, AlertRecordVo record);

    /** 在调用方事务内为规则配置的每个渠道登记一条 pending 通知行，返回排程结果。 */
    List<AlertNotificationEntity> scheduleNotifications(AlertRuleEntity rule, AlertRecordVo record);

    /** 异步尝试发送已排程的通知（每条渠道首次尝试一次）。 */
    void sendScheduled(Long recordId, List<AlertNotificationEntity> notifications,
            AlertRuleEntity rule, AlertRecordVo record);

    /** 重试一条到期未送达的 pending 通知（调度器调用）。 */
    void retry(AlertNotificationEntity notification, AlertRuleEntity rule, AlertRecordVo record);
}
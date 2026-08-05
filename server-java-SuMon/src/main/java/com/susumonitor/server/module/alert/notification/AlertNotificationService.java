package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;

/**
 * 告警外部通知服务契约。
 *
 * <p>按规则上配置的通知渠道（邮件/钉钉/自定义 Webhook）发送通知，
 * 每个渠道在 alert_notifications 表记一行（V21）并支持退避重试；
 * 通知失败不影响告警记录本身。</p>
 */
public interface AlertNotificationService {

    /** 按规则配置的渠道首次异步发送通知。 */
    void notify(AlertRuleEntity rule, AlertRecordVo record);

    /** 重试一条到期未送达的 pending 通知（调度器调用）。 */
    void retry(AlertNotificationEntity notification, AlertRuleEntity rule, AlertRecordVo record);
}

package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;

/**
 * 告警外部通知服务契约。
 *
 * <p>根据规则上配置的通知渠道（邮件/钉钉/自定义 Webhook）异步发送通知，
 * 通知失败不影响告警记录本身。发送完成后回写记录的通知时间与渠道。</p>
 */
public interface AlertNotificationService {

    /** 按规则配置的渠道异步发送外部通知。 */
    void notify(AlertRuleEntity rule, AlertRecordVo record);
}

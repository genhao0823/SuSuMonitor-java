package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.service.AlertTriggeredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * 在告警评估事务提交后触发外部通知，与 AlertPushPublisher（WebSocket 推送）并列。
 *
 * <p>仅当规则仍存在、已启用且配置了至少一个通知渠道时才发送；
 * 规则被软删除后不再发送通知。发送动作交给 @Async 的
 * {@link AlertNotificationService}，不阻塞事务提交后的监听线程。</p>
 */
@Component
@RequiredArgsConstructor
public class AlertNotificationPublisher {

    private final AlertRuleMapper ruleMapper;
    private final AlertNotificationService notificationService;

    /** 消费 AlertTriggeredEvent，在告警事务提交后按规则通知配置发送外部通知。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertTriggered(AlertTriggeredEvent event) {
        AlertRuleEntity rule = ruleMapper.selectActiveRuleById(event.record().getRuleId());
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled()) || !hasAnyChannel(rule)) {
            return;
        }
        notificationService.notify(rule, event.record());
    }

    /** 判断规则是否配置了至少一个通知渠道。 */
    private boolean hasAnyChannel(AlertRuleEntity rule) {
        return StringUtils.hasText(rule.getNotifyEmail())
                || StringUtils.hasText(rule.getNotifyDingtalk())
                || StringUtils.hasText(rule.getNotifyWebhook());
    }
}

package com.susumonitor.server.module.alert.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.service.AlertTriggeredEvent;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import org.junit.jupiter.api.Test;

/** 验证通知发布器只在规则存在、启用且配置了渠道时触发外部通知。 */
class AlertNotificationPublisherTests {

    private final AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
    private final AlertNotificationService notificationService = mock(AlertNotificationService.class);
    private final AlertNotificationPublisher publisher =
            new AlertNotificationPublisher(ruleMapper, notificationService);

    private AlertTriggeredEvent event() {
        AlertRecordVo record = new AlertRecordVo();
        record.setId(1L);
        record.setRuleId(9L);
        record.setServerId(7L);
        return new AlertTriggeredEvent(7L, record);
    }

    private AlertRuleEntity rule(String email) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(9L);
        rule.setEnabled(true);
        rule.setNotifyEmail(email);
        return rule;
    }

    @Test
    void shouldNotifyWhenRuleEnabledWithChannel() {
        when(ruleMapper.selectActiveRuleById(9L)).thenReturn(rule("ops@example.com"));
        publisher.onAlertTriggered(event());
        verify(notificationService).notify(any(), any());
    }

    @Test
    void shouldSkipWhenRuleDeleted() {
        when(ruleMapper.selectActiveRuleById(9L)).thenReturn(null);
        publisher.onAlertTriggered(event());
        verify(notificationService, never()).notify(any(), any());
    }

    @Test
    void shouldSkipWhenRuleDisabled() {
        AlertRuleEntity rule = rule("ops@example.com");
        rule.setEnabled(false);
        when(ruleMapper.selectActiveRuleById(9L)).thenReturn(rule);
        publisher.onAlertTriggered(event());
        verify(notificationService, never()).notify(any(), any());
    }

    @Test
    void shouldSkipWhenNoChannelConfigured() {
        when(ruleMapper.selectActiveRuleById(9L)).thenReturn(rule(null));
        publisher.onAlertTriggered(event());
        verify(notificationService, never()).notify(any(), any());
    }
}

package com.susumonitor.server.module.alert.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证通知重试调度器：到期重试、规则失效置 failed、记录缺失置 failed。 */
class NotificationRetrySchedulerTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);

    private final AlertNotificationMapper notificationMapper = mock(AlertNotificationMapper.class);
    private final AlertRecordMapper recordMapper = mock(AlertRecordMapper.class);
    private final AlertRuleMapper ruleMapper = mock(AlertRuleMapper.class);
    private final AlertNotificationService notificationService = mock(AlertNotificationService.class);
    private final NotificationRetryScheduler scheduler =
            new NotificationRetryScheduler(notificationMapper, recordMapper, ruleMapper, notificationService, CLOCK);

    private AlertNotificationEntity pending(Long id, int attempts) {
        AlertNotificationEntity notification = new AlertNotificationEntity();
        notification.setId(id);
        notification.setAlertRecordId(10L);
        notification.setChannel("dingtalk");
        notification.setStatus("pending");
        notification.setAttempts(attempts);
        return notification;
    }

    private AlertRecordEntity record() {
        AlertRecordEntity record = new AlertRecordEntity();
        record.setId(10L);
        record.setRuleId(20L);
        record.setServerId(7L);
        return record;
    }

    private AlertRuleEntity rule() {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(20L);
        rule.setEnabled(true);
        rule.setNotifyDingtalk("https://dingtalk");
        return rule;
    }

    @Test
    void shouldRetryDuePendingNotifications() {
        when(notificationMapper.selectPendingForRetry(any(), anyInt())).thenReturn(List.of(pending(1L, 1)));
        when(recordMapper.selectRecordById(10L)).thenReturn(record());
        when(ruleMapper.selectActiveRuleById(20L)).thenReturn(rule());

        scheduler.retryPendingNotifications();

        verify(notificationService).retry(any(), any(), any());
    }

    @Test
    void shouldMarkFailedWhenRuleDeletedOrDisabled() {
        when(notificationMapper.selectPendingForRetry(any(), anyInt())).thenReturn(List.of(pending(2L, 3)));
        when(recordMapper.selectRecordById(10L)).thenReturn(record());
        when(ruleMapper.selectActiveRuleById(20L)).thenReturn(null);

        scheduler.retryPendingNotifications();

        verify(notificationMapper).markAttempt(any(), anyString(), any(), any(), anyString());
        verify(notificationService, never()).retry(any(), any(), any());
    }

    @Test
    void shouldMarkFailedWhenRecordMissing() {
        when(notificationMapper.selectPendingForRetry(any(), anyInt())).thenReturn(List.of(pending(3L, 1)));
        when(recordMapper.selectRecordById(10L)).thenReturn(null);

        scheduler.retryPendingNotifications();

        verify(notificationMapper).markAttempt(any(), anyString(), any(), any(), anyString());
        verify(notificationService, never()).retry(any(), any(), any());
    }

    @Test
    void shouldSkipWhenNothingDue() {
        when(notificationMapper.selectPendingForRetry(any(), anyInt())).thenReturn(List.of());
        scheduler.retryPendingNotifications();
        verify(notificationService, never()).retry(any(), any(), any());
        verify(notificationMapper, never()).markAttempt(any(), any(), any(), any(), any());
    }
}

package com.susumonitor.server.module.alert.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

/** 验证告警外部通知的渠道分发、退避重试与通知状态回写。 */
class AlertNotificationServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);

    private AppProperties appProperties;
    private AlertRecordMapper recordMapper;
    private AlertNotificationMapper notificationMapper;
    private RestTemplate restTemplate;
    private JavaMailSender mailSender;
    private AlertNotificationService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAlert().setNotificationEnabled(true);
        recordMapper = mock(AlertRecordMapper.class);
        notificationMapper = mock(AlertNotificationMapper.class);
        restTemplate = mock(RestTemplate.class);
        mailSender = mock(JavaMailSender.class);
        service = new AlertNotificationServiceImpl(appProperties, recordMapper, notificationMapper,
                restTemplate, Optional.of(mailSender), CLOCK);
    }

    private AlertRuleEntity rule(String email, String dingtalk, String webhook) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(10L);
        rule.setLevel("warning");
        rule.setMetric("cpu");
        rule.setThresholdValue(new BigDecimal("80"));
        rule.setNotifyEmail(email);
        rule.setNotifyDingtalk(dingtalk);
        rule.setNotifyWebhook(webhook);
        return rule;
    }

    private AlertRecordVo record() {
        AlertRecordVo record = new AlertRecordVo();
        record.setId(100L);
        record.setServerId(7L);
        record.setMetric("cpu");
        record.setCurrentValue(new BigDecimal("92.30"));
        record.setThresholdValue(new BigDecimal("80"));
        record.setLevel("warning");
        record.setMessage("cpu high");
        return record;
    }

    /** 邮件渠道：启用总开关时发送并回写 email 渠道。 */
    @Test
    void shouldSendEmailWhenEnabledAndConfigured() {
        service.notify(rule("ops@example.com", null, null), record());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertEquals("noreply@susumonitor.local", message.getFrom());
        assertEquals("ops@example.com", message.getTo()[0]);
        assertEquals("[告警] WARNING cpu 超过阈值（当前 92.3）", message.getSubject());
        verify(notificationMapper).markAttempt(any(), eq("sent"), eq(1), isNull(), isNull());
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("email"));
    }

    /** 邮件总开关关闭时即使配置了收件人也不发送邮件。 */
    @Test
    void shouldNotSendEmailWhenDisabledGlobally() {
        appProperties.getAlert().setNotificationEnabled(false);
        service.notify(rule("ops@example.com", null, null), record());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(notificationMapper, never()).insert(any());
        verify(recordMapper, never()).updateNotifiedInfo(any(), any(), anyString());
    }

    /** 未配置收件人时不发送邮件，也不回写通知状态。 */
    @Test
    void shouldNotSendEmailWithoutRecipient() {
        service.notify(rule(null, null, null), record());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(recordMapper, never()).updateNotifiedInfo(any(), any(), anyString());
    }

    /** 钉钉渠道：POST 文本消息并回写 dingtalk 渠道。 */
    @Test
    void shouldPostDingtalkText() {
        service.notify(rule(null, "https://oapi.dingtalk.com/robot/send?access_token=t", null), record());

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForObject(
                eq("https://oapi.dingtalk.com/robot/send?access_token=t"), bodyCaptor.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) bodyCaptor.getValue();
        assertEquals("text", body.get("msgtype"));
        verify(notificationMapper).markAttempt(any(), eq("sent"), eq(1), isNull(), isNull());
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("dingtalk"));
    }

    /** Webhook 渠道：POST 告警记录 VO 并回写 webhook 渠道。 */
    @Test
    void shouldPostWebhookRecord() {
        service.notify(rule(null, null, "https://hooks.example.com/alert"), record());
        verify(restTemplate).postForObject(eq("https://hooks.example.com/alert"), eq(record()), eq(String.class));
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("webhook"));
    }

    /** 多渠道：按 email,dingtalk,webhook 顺序拼接回写。 */
    @Test
    void shouldCombineSentChannels() {
        service.notify(rule("ops@example.com", "https://dingtalk", "https://webhook"), record());
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("email,dingtalk,webhook"));
    }

    /** 单渠道失败不影响其他成功渠道，且 channels 只记录成功渠道。 */
    @Test
    void shouldTolerateChannelFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        service.notify(rule("ops@example.com", "https://dingtalk", null), record());
        verify(notificationMapper).markAttempt(any(), eq("pending"), eq(1), any(), anyString());
        verify(notificationMapper).markAttempt(any(), eq("sent"), eq(1), isNull(), isNull());
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("dingtalk"));
    }

    /** 全部渠道失败时不回写 notified_at/notify_channels，前端可识别"发送失败"。 */
    @Test
    void shouldNotWriteNotifiedWhenAllChannelsFail() {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        org.mockito.Mockito.doThrow(new RuntimeException("webhook down"))
                .when(restTemplate).postForObject(anyString(), any(), eq(String.class));
        service.notify(rule("ops@example.com", null, "https://webhook"), record());
        verify(recordMapper, never()).updateNotifiedInfo(any(), any(), anyString());
    }

    /** 无 JavaMailSender（SMTP 未配置）时邮件渠道被跳过。 */
    @Test
    void shouldSkipEmailWhenMailSenderAbsent() {
        service = new AlertNotificationServiceImpl(appProperties, recordMapper, notificationMapper,
                restTemplate, Optional.empty(), CLOCK);
        service.notify(rule("ops@example.com", "https://dingtalk", null), record());
        verify(restTemplate).postForObject(eq("https://dingtalk"), any(), eq(String.class));
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("dingtalk"));
    }

    /** 退避时间：2^attempts 秒，上限 60 秒。 */
    @Test
    void backoffSecondsShouldGrowExponentiallyWithCap() {
        assertEquals(2, AlertNotificationServiceImpl.backoffSeconds(1));
        assertEquals(4, AlertNotificationServiceImpl.backoffSeconds(2));
        assertEquals(8, AlertNotificationServiceImpl.backoffSeconds(3));
        assertEquals(16, AlertNotificationServiceImpl.backoffSeconds(4));
        assertEquals(60, AlertNotificationServiceImpl.backoffSeconds(6));
        assertEquals(60, AlertNotificationServiceImpl.backoffSeconds(10));
    }

    /** 失败原因摘要截断到 last_error 列长度。 */
    @Test
    void rootMessageShouldBeTruncated() {
        RuntimeException nested = new RuntimeException("x".repeat(1000));
        String summary = AlertNotificationServiceImpl.rootMessage(nested);
        assertTrue(summary.length() <= AlertNotificationServiceImpl.MAX_ERROR_LENGTH);
    }

    /** 重试：对一条 pending 通知再尝试一次，尝试次数递增。 */
    @Test
    void retryShouldIncrementAttempts() {
        var notification = new com.susumonitor.server.module.alert.entity.AlertNotificationEntity();
        notification.setId(55L);
        notification.setChannel("dingtalk");
        notification.setAttempts(1);
        service.retry(notification, rule(null, "https://dingtalk", null), record());
        verify(restTemplate).postForObject(eq("https://dingtalk"), any(), eq(String.class));
        verify(notificationMapper).markAttempt(eq(55L), eq("sent"), eq(2), isNull(), isNull());
    }

    /** scheduleNotifications 只排程（调用方事务内插 pending 行），不发送不回写。 */
    @Test
    void scheduleNotificationsOnlyInsertsPendingRows() {
        List<AlertNotificationEntity> rows = service.scheduleNotifications(
                rule("ops@example.com", "https://dingtalk", null), record());

        assertEquals(2, rows.size());
        assertEquals("email", rows.get(0).getChannel());
        assertEquals("dingtalk", rows.get(1).getChannel());
        verify(notificationMapper, times(2)).insert(any());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(recordMapper, never()).updateNotifiedInfo(any(), any(), anyString());
    }

    /** sendScheduled 对已排程行逐渠道发送并回写成功渠道（消息驱动链路分离可再用性）。 */
    @Test
    void sendScheduledSendsScheduledRowsAndWritesBack() {
        var email = new com.susumonitor.server.module.alert.entity.AlertNotificationEntity();
        email.setId(1L);
        email.setChannel("email");
        var webhook = new com.susumonitor.server.module.alert.entity.AlertNotificationEntity();
        webhook.setId(2L);
        webhook.setChannel("webhook");
        List<AlertNotificationEntity> rows = List.of(email, webhook);

        service.sendScheduled(100L, rows, rule("ops@example.com", null, "https://hooks.example.com/alert"), record());

        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(restTemplate).postForObject(eq("https://hooks.example.com/alert"), eq(record()), eq(String.class));
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("email,webhook"));
    }

    /** sendScheduled 全部渠道失败时一律不回写通知时间（与 notify 组合入口语义一致）。 */
    @Test
    void sendScheduledAllFailedDoesNotWriteBack() {
        var email = new com.susumonitor.server.module.alert.entity.AlertNotificationEntity();
        email.setId(1L);
        email.setChannel("email");
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down")).when(mailSender)
                .send(any(SimpleMailMessage.class));

        service.sendScheduled(100L, List.of(email), rule("ops@example.com", null, null), record());

        verify(recordMapper, never()).updateNotifiedInfo(any(), any(), anyString());
        verify(notificationMapper).markAttempt(any(), eq("pending"), eq(1), any(), anyString());
    }
}

package com.susumonitor.server.module.alert.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

/** 验证告警外部通知的渠道分发、失败隔离与通知状态回写。 */
class AlertNotificationServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);

    private AppProperties appProperties;
    private AlertRecordMapper recordMapper;
    private RestTemplate restTemplate;
    private JavaMailSender mailSender;
    private AlertNotificationService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAlert().setNotificationEnabled(true);
        recordMapper = mock(AlertRecordMapper.class);
        restTemplate = mock(RestTemplate.class);
        mailSender = mock(JavaMailSender.class);
        service = new AlertNotificationServiceImpl(appProperties, recordMapper, restTemplate,
                Optional.of(mailSender), CLOCK);
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
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("email"));
    }

    /** 邮件总开关关闭时即使配置了收件人也不发送邮件。 */
    @Test
    void shouldNotSendEmailWhenDisabledGlobally() {
        appProperties.getAlert().setNotificationEnabled(false);
        service.notify(rule("ops@example.com", null, null), record());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
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

    /** 通知发送失败不影响告警记录主流程（只记录日志，不抛异常）。 */
    @Test
    void shouldTolerateChannelFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        service.notify(rule("ops@example.com", "https://dingtalk", null), record());
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("email,dingtalk"));
    }

    /** 无 JavaMailSender（SMTP 未配置）时邮件渠道被跳过。 */
    @Test
    void shouldSkipEmailWhenMailSenderAbsent() {
        service = new AlertNotificationServiceImpl(appProperties, recordMapper, restTemplate,
                Optional.empty(), CLOCK);
        service.notify(rule("ops@example.com", "https://dingtalk", null), record());
        verify(restTemplate).postForObject(eq("https://dingtalk"), any(), eq(String.class));
        verify(recordMapper).updateNotifiedInfo(eq(100L), any(), eq("dingtalk"));
    }
}

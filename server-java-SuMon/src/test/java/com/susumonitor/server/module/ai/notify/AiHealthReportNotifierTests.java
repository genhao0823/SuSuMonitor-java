package com.susumonitor.server.module.ai.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.vo.AiHealthReportVo;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestTemplate;

/**
 * 验证健康报告通知器：渠道开关/缺配置静默跳过、邮件与 Webhook 载荷内容、
 * 以及发送失败不影响调用方（尽力而为语义）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiHealthReportNotifierTests {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private JavaMailSender mailSender;

    private AppProperties appProperties;
    private AiHealthReportNotifier notifier;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAi().getReport().setEnabled(true);
        notifier = new AiHealthReportNotifier(appProperties, restTemplate, Optional.of(mailSender));
    }

    /** notify-enabled 关闭：不触发任何渠道。 */
    @Test
    void dispatchShouldSkipWhenNotificationDisabled() {
        AiHealthReportVo report = report();

        notifier.dispatch(report);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    /** 渠道均未配置：不触发任何渠道。 */
    @Test
    void dispatchShouldSkipWhenNoChannelConfigured() {
        appProperties.getAi().getReport().setNotifyEnabled(true);
        AiHealthReportVo report = report();

        notifier.dispatch(report);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    /** 配置邮件与 Webhook 后逐渠道推送，邮件正文带状态与关注点。 */
    @Test
    void dispatchShouldSendEmailAndWebhook() {
        appProperties.getAi().getReport().setNotifyEnabled(true);
        appProperties.getAi().getReport().setNotifyEmail("ops@example.com");
        appProperties.getAi().getReport().setNotifyWebhook("https://hooks.example.com/susumonitor");
        appProperties.getAlert().setMailFrom("noreply@susumonitor.local");
        AiHealthReportVo report = report();

        notifier.dispatch(report);

        ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mailCaptor.capture());
        assertEquals("noreply@susumonitor.local", mailCaptor.getValue().getFrom());
        assertEquals("ops@example.com", mailCaptor.getValue().getTo()[0]);
        org.junit.jupiter.api.Assertions.assertTrue(
                mailCaptor.getValue().getText().contains("succeeded"));
        org.junit.jupiter.api.Assertions.assertTrue(
                mailCaptor.getValue().getText().contains("Disk peak on server #7"));
        verify(restTemplate).postForObject(eq("https://hooks.example.com/susumonitor"), any(), eq(String.class));
    }

    /** 发送异常只被吞掉：dispatch 不向上抛出。 */
    @Test
    void dispatchShouldSwallowSendFailures() {
        appProperties.getAi().getReport().setNotifyEnabled(true);
        appProperties.getAi().getReport().setNotifyDingtalk("https://oapi.dingtalk.com/robot");
        when(restTemplate.postForObject(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("network down"));
        AiHealthReportVo report = report();

        notifier.dispatch(report);

        verify(restTemplate).postForObject(anyString(), any(), any());
    }

    private AiHealthReportVo report() {
        AiHealthReportVo vo = new AiHealthReportVo();
        vo.setId(1L);
        vo.setReportDate(LocalDate.of(2026, 9, 10));
        vo.setStatus("succeeded");
        vo.setSummary("All servers healthy.");
        vo.setTopConcerns(List.of("Disk peak on server #7"));
        vo.setLimitations(List.of("Snapshot-based offline list."));
        return vo;
    }
}

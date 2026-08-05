package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * 告警外部通知服务实现。
 *
 * <p>按规则配置的渠道顺序发送：邮件（需启用总开关且配置 SMTP）、钉钉机器人、
 * 自定义 Webhook。任一渠道失败只记录告警日志，不抛出影响主流程的异常；
 * 至少一个渠道发送成功后回写记录的通知时间与渠道（email/dingtalk/webhook）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotificationServiceImpl implements AlertNotificationService {

    private final AppProperties appProperties;
    private final AlertRecordMapper recordMapper;
    private final RestTemplate restTemplate;
    private final Optional<JavaMailSender> mailSender;
    private final Clock clock;

    /** 异步发送规则配置的所有通知渠道，并回写记录通知状态。 */
    @Async
    @Override
    public void notify(AlertRuleEntity rule, AlertRecordVo record) {
        List<String> sentChannels = new ArrayList<>();
        if (appProperties.getAlert().isNotificationEnabled()
                && mailSender.isPresent() && StringUtils.hasText(rule.getNotifyEmail())
                && sendEmail(rule.getNotifyEmail(), rule, record)) {
            sentChannels.add("email");
        }
        if (StringUtils.hasText(rule.getNotifyDingtalk())
                && sendDingtalk(rule.getNotifyDingtalk(), rule, record)) {
            sentChannels.add("dingtalk");
        }
        if (StringUtils.hasText(rule.getNotifyWebhook())
                && sendWebhook(rule.getNotifyWebhook(), rule, record)) {
            sentChannels.add("webhook");
        }
        // 仅在至少一个渠道发送成功后回写通知时间与成功渠道；
        // 全部失败不回写 notified_at，前端可据此识别"发送失败"。
        if (!sentChannels.isEmpty()) {
            recordMapper.updateNotifiedInfo(record.getId(), LocalDateTime.now(clock),
                    String.join(",", sentChannels));
        }
    }

    private boolean sendEmail(String to, AlertRuleEntity rule, AlertRecordVo record) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(appProperties.getAlert().getMailFrom());
            message.setTo(to.split(","));
            message.setSubject(AlertMessageBuilder.subject(rule, record));
            message.setText(AlertMessageBuilder.body(rule, record));
            mailSender.get().send(message);
            log.info("alert email notification sent to {}", to);
            return true;
        } catch (Exception exception) {
            log.warn("alert email notification failed, ruleId={}: {}", rule.getId(), exception.getMessage());
            return false;
        }
    }

    private boolean sendDingtalk(String url, AlertRuleEntity rule, AlertRecordVo record) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("msgtype", "text");
            payload.put("text", Map.of("content", AlertMessageBuilder.dingtalkText(rule, record)));
            restTemplate.postForObject(url, payload, String.class);
            log.info("alert dingtalk notification sent");
            return true;
        } catch (Exception exception) {
            log.warn("alert dingtalk notification failed, ruleId={}: {}", rule.getId(), exception.getMessage());
            return false;
        }
    }

    private boolean sendWebhook(String url, AlertRuleEntity rule, AlertRecordVo record) {
        try {
            restTemplate.postForObject(url, record, String.class);
            log.info("alert webhook notification sent");
            return true;
        } catch (Exception exception) {
            log.warn("alert webhook notification failed, ruleId={}: {}", rule.getId(), exception.getMessage());
            return false;
        }
    }
}

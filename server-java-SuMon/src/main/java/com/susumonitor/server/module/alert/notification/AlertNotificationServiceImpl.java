package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
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
 * <p>按规则配置的渠道逐一发送：邮件（另需 SMTP 配置）、钉钉机器人、
 * 自定义 Webhook（均受总开关约束）。每个渠道在 alert_notifications 表记一行（V21），首次立即尝试；
 * 失败按 2^attempts 秒（上限 60s）退避并记录 last_error，达重试上限置 failed。
 * 至少一个渠道送达后回写记录的通知时间与成功渠道；全部失败不回写，前端可识别"发送失败"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotificationServiceImpl implements AlertNotificationService {

    /** 单渠道最大尝试次数（首次 + 重试）。 */
    static final int MAX_ATTEMPTS = 5;
    /** 重试退避上限（秒）。 */
    static final int MAX_BACKOFF_SECONDS = 60;
    /** 与 V21 last_error 列长度一致。 */
    static final int MAX_ERROR_LENGTH = 500;

    private final AppProperties appProperties;
    private final AlertRecordMapper recordMapper;
    private final AlertNotificationMapper notificationMapper;
    private final RestTemplate restTemplate;
    private final Optional<JavaMailSender> mailSender;
    private final Clock clock;

    /** 首次发送：为每个配置渠道建 pending 行并立即尝试一次（内部串行：自调用不经代理）。 */
    @Async
    @Override
    public void notify(AlertRuleEntity rule, AlertRecordVo record) {
        List<AlertNotificationEntity> notifications = scheduleNotifications(rule, record);
        sendScheduled(record.getId(), notifications, rule, record);
    }

    /** 在调用方事务内为每个配置渠道登记 pending 行；返回排程结果供提交后发送。 */
    @Override
    public List<AlertNotificationEntity> scheduleNotifications(AlertRuleEntity rule, AlertRecordVo record) {
        List<AlertNotificationEntity> notifications = new ArrayList<>();
        for (String channel : configuredChannels(rule)) {
            notifications.add(insertPending(record.getId(), channel));
        }
        return notifications;
    }

    /** 异步尝试发送已排程的通知：逐渠道首次尝试，至少一个渠道送达后回写通知时间。 */
    @Async
    @Override
    public void sendScheduled(Long recordId, List<AlertNotificationEntity> notifications,
            AlertRuleEntity rule, AlertRecordVo record) {
        List<String> sentChannels = new ArrayList<>();
        for (AlertNotificationEntity notification : notifications) {
            if (attemptChannel(notification.getId(), notification.getChannel(), rule, record, 1)) {
                sentChannels.add(notification.getChannel());
            }
        }
        // 仅在至少一个渠道送达后回写通知时间与成功渠道。
        if (!sentChannels.isEmpty()) {
            recordMapper.updateNotifiedInfo(recordId, LocalDateTime.now(clock),
                    String.join(",", sentChannels));
        }
    }

    /** 调度器调用：对一条到期的 pending 通知再尝试一次。 */
    @Override
    public void retry(AlertNotificationEntity notification, AlertRuleEntity rule, AlertRecordVo record) {
        int attempt = (notification.getAttempts() == null ? 0 : notification.getAttempts()) + 1;
        attemptChannel(notification.getId(), notification.getChannel(), rule, record, attempt);
    }

    /** 规则配置的非空渠道（总开关关闭时不发送任何渠道；邮件另需 SMTP 配置）。 */
    private List<String> configuredChannels(AlertRuleEntity rule) {
        List<String> channels = new ArrayList<>();
        if (!appProperties.getAlert().isNotificationEnabled()) {
            return channels;
        }
        if (mailSender.isPresent() && StringUtils.hasText(rule.getNotifyEmail())) {
            channels.add("email");
        }
        if (StringUtils.hasText(rule.getNotifyDingtalk())) {
            channels.add("dingtalk");
        }
        if (StringUtils.hasText(rule.getNotifyWebhook())) {
            channels.add("webhook");
        }
        return channels;
    }

    /** 插入一条 pending 状态的通知记录。 */
    private AlertNotificationEntity insertPending(Long recordId, String channel) {
        AlertNotificationEntity entity = new AlertNotificationEntity();
        entity.setAlertRecordId(recordId);
        entity.setChannel(channel);
        entity.setStatus("pending");
        entity.setAttempts(0);
        notificationMapper.insert(entity);
        return entity;
    }

    /** 尝试一次发送；成功置 sent，失败按退避排期重试或达上限置 failed。 */
    private boolean attemptChannel(Long notificationId, String channel, AlertRuleEntity rule,
            AlertRecordVo record, int attempt) {
        try {
            switch (channel) {
                case "email" -> sendEmail(rule.getNotifyEmail(), rule, record);
                case "dingtalk" -> sendDingtalk(rule.getNotifyDingtalk(), rule, record);
                case "webhook" -> sendWebhook(rule.getNotifyWebhook(), rule, record);
                default -> throw new IllegalStateException("unsupported notification channel: " + channel);
            }
            notificationMapper.markAttempt(notificationId, "sent", attempt, null, null);
            log.info("alert {} notification delivered, notificationId={}", channel, notificationId);
            return true;
        } catch (Exception exception) {
            log.warn("alert {} notification failed, ruleId={}: {}", channel, rule.getId(), exception.getMessage());
            String error = truncate(rootMessage(exception));
            boolean giveUp = attempt >= MAX_ATTEMPTS;
            LocalDateTime nextAttemptAt = giveUp ? null
                    : LocalDateTime.now(clock).plusSeconds(backoffSeconds(attempt));
            notificationMapper.markAttempt(notificationId, giveUp ? "failed" : "pending",
                    attempt, nextAttemptAt, error);
            return false;
        }
    }

    /** 指数退避：2^attempts 秒，上限 MAX_BACKOFF_SECONDS（复用 outbox markRetry 语义）。 */
    static long backoffSeconds(int attempt) {
        return Math.min(1L << attempt, MAX_BACKOFF_SECONDS);
    }

    /** 发送邮件通知。 */
    private void sendEmail(String to, AlertRuleEntity rule, AlertRecordVo record) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(appProperties.getAlert().getMailFrom());
        message.setTo(to.split(","));
        message.setSubject(AlertMessageBuilder.subject(rule, record));
        message.setText(AlertMessageBuilder.body(rule, record));
        mailSender.get().send(message);
    }

    /** 发送钉钉机器人通知。 */
    private void sendDingtalk(String url, AlertRuleEntity rule, AlertRecordVo record) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("msgtype", "text");
        payload.put("text", Map.of("content", AlertMessageBuilder.dingtalkText(rule, record)));
        restTemplate.postForObject(url, payload, String.class);
    }

    /** 发送自定义 Webhook 通知。 */
    private void sendWebhook(String url, AlertRuleEntity rule, AlertRecordVo record) {
        restTemplate.postForObject(url, record, String.class);
    }

    /** 取根因摘要：类名 + 消息，截断到 last_error 列长度。 */
    static String rootMessage(Throwable cause) {
        Throwable current = cause;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getClass().getSimpleName()
                + (current.getMessage() != null ? ": " + current.getMessage() : "");
        return truncate(message);
    }

    /** 截断字符串到最大错误长度。 */
    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}

package com.susumonitor.server.module.command;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * 策略自动审批的事后通知器（M2 收口，尽力而为）：auto 审批的命令运行到达终态
 * （succeeded/failed/timeout）后，按服务级配置渠道推送一条结果通知
 * （邮件/钉钉/Webhook，渠道读 {@code susumonitor.ai.command.auto-approval.notify-*}）。
 *
 * <p>与告警主通知（alert_notifications 状态机 + 重试调度）刻意分离：运行结果本身
 * 已持久化并可经运行记录 API 审计，发送失败只记日志、不重试、不建 pending 行，
 * 绝不影响命令状态机与结果回填主链路。独立 notify-enabled 开关，
 * 不耦合告警通知总开关。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandAutoApprovalNotifier {

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final Optional<JavaMailSender> mailSender;

    /** 注入配置与外部通道客户端；不落任何通知状态行。 */
    public CommandAutoApprovalNotifier(AppProperties appProperties, RestTemplate restTemplate,
            Optional<JavaMailSender> mailSender) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplate;
        this.mailSender = mailSender;
    }

    /**
     * 尽力而为推送一次自动审批运行的结果通知；任何异常只记日志，绝不向上抛出
     * （结果已落库，抛出只会把回填/扫描主链路拖入无意义失败）。
     *
     * @param run 已到达终态的运行记录（须为 approval_mode='auto'）
     * @param finalStatus 终态：succeeded/failed/timeout
     */
    public void notifyCompletion(CommandRunEntity run, String finalStatus) {
        try {
            dispatch(run, finalStatus);
        } catch (Exception exception) {
            log.warn("command auto-approval notification failed, runId={}: {}",
                    run.getId(), exception.getMessage());
        }
    }

    /** 逐渠道推送；总开关关闭或未配置任何渠道时静默跳过。 */
    private void dispatch(CommandRunEntity run, String finalStatus) {
        AppProperties.Ai.Command.AutoApproval config = appProperties.getAi().getCommand().getAutoApproval();
        if (!config.isNotifyEnabled()) {
            return;
        }
        boolean sent = false;
        if (mailSender.isPresent() && StringUtils.hasText(config.getNotifyEmail())) {
            sendEmail(run, finalStatus, config);
            sent = true;
        }
        if (StringUtils.hasText(config.getNotifyDingtalk())) {
            sendDingtalk(config.getNotifyDingtalk(), run, finalStatus);
            sent = true;
        }
        if (StringUtils.hasText(config.getNotifyWebhook())) {
            sendWebhook(config.getNotifyWebhook(), run, finalStatus);
            sent = true;
        }
        if (!sent) {
            log.info("command auto-approval notification skipped: no channel configured, runId={}", run.getId());
        }
    }

    /** 发送结果通知邮件（纯文本：模板 + 终态 + 执行摘要）。 */
    private void sendEmail(CommandRunEntity run, String finalStatus,
            AppProperties.Ai.Command.AutoApproval config) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(appProperties.getAlert().getMailFrom());
        message.setTo(config.getNotifyEmail().split(","));
        message.setSubject("[AI 命令] auto-approved run #" + run.getId() + " " + finalStatus);
        message.setText(buildText(run, finalStatus));
        mailSender.get().send(message);
    }

    /** 发送钉钉文本消息。 */
    private void sendDingtalk(String url, CommandRunEntity run, String finalStatus) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("msgtype", "text");
        payload.put("text", Map.of("content", "[AI 命令] auto-approved run #" + run.getId()
                + " (" + run.getTemplateId() + ") on server #" + run.getServerId()
                + " finished: " + finalStatus));
        restTemplate.postForObject(url, payload, String.class);
    }

    /** 发送自定义 Webhook：结构化结果载荷（type 标识消息类别，供接收方路由）。 */
    private void sendWebhook(String url, CommandRunEntity run, String finalStatus) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ai.command.auto_approval_result");
        payload.put("run_id", run.getId());
        payload.put("execution_id", run.getExecutionId());
        payload.put("server_id", run.getServerId());
        payload.put("template_id", run.getTemplateId());
        payload.put("risk_level", run.getRiskLevel());
        payload.put("status", finalStatus);
        payload.put("exit_code", run.getExitCode());
        payload.put("duration_ms", run.getDurationMs());
        restTemplate.postForObject(url, payload, String.class);
    }

    /** 组装纯文本正文：终态 + 模板/风险 + 执行摘要；命令正文以审计行为准，不在通知中展开。 */
    private String buildText(CommandRunEntity run, String finalStatus) {
        StringBuilder text = new StringBuilder("[AI 命令] Auto-approved command run #").append(run.getId())
                .append(" finished: ").append(finalStatus).append('\n')
                .append("Template: ").append(run.getTemplateId())
                .append(" (risk ").append(run.getRiskLevel()).append(")\n")
                .append("Server: #").append(run.getServerId()).append('\n');
        if (run.getExitCode() != null) {
            text.append("Exit code: ").append(run.getExitCode()).append('\n');
        }
        if (run.getDurationMs() != null) {
            text.append("Duration: ").append(run.getDurationMs()).append(" ms\n");
        }
        text.append("\nFull output is available in the command run audit record.");
        return text.toString();
    }
}

package com.susumonitor.server.module.ai.notify;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.vo.AiHealthReportVo;
import java.util.HashMap;
import java.util.List;
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
 * AI 定时健康报告通知器（尽力而为）：报告落库后按服务级配置渠道推送
 * （邮件/钉钉/Webhook，渠道读 {@code susumonitor.ai.report.notify-*}）。
 *
 * <p>与告警主通知（alert_notifications 状态机 + 重试调度）刻意分离：报告本身
 * 已持久化并可经回看 API 获取，发送失败只记日志、不重试、不建 pending 行，
 * 绝不影响报告生成主链路。独立 notify-enabled 开关，不耦合告警通知总开关。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.report.enabled", havingValue = "true")
public class AiHealthReportNotifier {

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final Optional<JavaMailSender> mailSender;

    /** 注入配置与外部通道客户端；不落任何通知状态行。 */
    public AiHealthReportNotifier(AppProperties appProperties, RestTemplate restTemplate,
            Optional<JavaMailSender> mailSender) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplate;
        this.mailSender = mailSender;
    }

    /**
     * 尽力而为推送报告通知；任何异常只记日志，绝不向上抛出
     * （报告已落库，抛出只会把生成主链路拖入无意义失败）。
     *
     * @param report 已落库的报告结果
     */
    public void dispatch(AiHealthReportVo report) {
        try {
            dispatch(report, appProperties.getAi().getReport());
        } catch (Exception exception) {
            log.warn("AI health report notification failed, reportDate={}: {}",
                    report.getReportDate(), exception.getMessage());
        }
    }

    /** 逐渠道推送；总开关关闭或未配置任何渠道时静默跳过。 */
    private void dispatch(AiHealthReportVo report, AppProperties.Ai.Report config) {
        if (!config.isNotifyEnabled()) {
            return;
        }
        boolean sent = false;
        if (mailSender.isPresent() && StringUtils.hasText(config.getNotifyEmail())) {
            sendEmail(report, config);
            sent = true;
        }
        if (StringUtils.hasText(config.getNotifyDingtalk())) {
            sendDingtalk(config.getNotifyDingtalk(), report);
            sent = true;
        }
        if (StringUtils.hasText(config.getNotifyWebhook())) {
            sendWebhook(config.getNotifyWebhook(), report);
            sent = true;
        }
        if (!sent) {
            log.info("AI health report notification skipped: no channel configured, reportDate={}",
                    report.getReportDate());
        }
    }

    /** 发送报告通知邮件（纯文本：状态 + 摘要 + 关注点清单）。 */
    private void sendEmail(AiHealthReportVo report, AppProperties.Ai.Report config) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(appProperties.getAlert().getMailFrom());
        message.setTo(config.getNotifyEmail().split(","));
        message.setSubject("[AI 报告] health report " + report.getReportDate()
                + " (" + report.getStatus() + ")");
        message.setText(buildText(report));
        mailSender.get().send(message);
    }

    /** 发送钉钉文本消息。 */
    private void sendDingtalk(String url, AiHealthReportVo report) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("msgtype", "text");
        payload.put("text", Map.of("content", "[AI 报告] " + report.getReportDate()
                + " (" + report.getStatus() + ")\n" + firstLine(report.getSummary())));
        restTemplate.postForObject(url, payload, String.class);
    }

    /** 发送自定义 Webhook：结构化报告载荷（type 标识消息类别，供接收方路由）。 */
    private void sendWebhook(String url, AiHealthReportVo report) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ai.health_report");
        payload.put("report_id", report.getId());
        payload.put("report_date", report.getReportDate() == null ? null : report.getReportDate().toString());
        payload.put("status", report.getStatus());
        payload.put("summary", report.getSummary());
        payload.put("top_concerns", report.getTopConcerns());
        payload.put("limitations", report.getLimitations());
        restTemplate.postForObject(url, payload, String.class);
    }

    /** 组装纯文本正文：状态 + 摘要 + 关注点/局限清单，全部来自已落库的报告内容。 */
    private String buildText(AiHealthReportVo report) {
        StringBuilder text = new StringBuilder("[AI 报告] Health report ")
                .append(report.getReportDate()).append(" (").append(report.getStatus()).append(")\n\n");
        if (report.getSummary() != null) {
            text.append(report.getSummary()).append('\n');
        }
        appendSection(text, "Top concerns", report.getTopConcerns());
        appendSection(text, "Limitations", report.getLimitations());
        if (report.getErrorCode() != null) {
            text.append("\nError code: ").append(report.getErrorCode()).append('\n');
        }
        return text.toString();
    }

    /** 追加一个编号清单小节。 */
    private void appendSection(StringBuilder text, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        text.append('\n').append(title).append(":\n");
        for (int index = 0; index < items.size(); index++) {
            text.append(index + 1).append(". ").append(items.get(index)).append('\n');
        }
    }

    /** 摘要首行（钉钉文本长度受限）；空摘要回退到状态描述。 */
    private String firstLine(String summary) {
        if (summary == null || summary.isBlank()) {
            return "Model summary unavailable; see stored report for aggregated facts.";
        }
        for (String line : summary.split("\n")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return summary;
    }
}

package com.susumonitor.server.module.ai.notify;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.model.AiAlertFacts;
import com.susumonitor.server.module.ai.vo.AiAlertExplanationVo;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
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
 * AI 告警解释补充通知器（尽力而为）：解释落库后按规则已配置渠道推送一条
 * "AI 解释"补充消息（邮件/钉钉/Webhook）。
 *
 * <p>与告警主通知（alert_notifications 状态机 + 重试调度）刻意分离：原告警通知
 * 已先行发出且带重试保障；本通知是对已持久化解释的增量触达，发送失败只记日志、
 * 不重试、不建 pending 行——解释本身已可经回看 API 获取，不因推送失败而丢失。
 * 受告警通知总开关 {@code susumonitor.alert.notification-enabled} 约束；
 * 规则已删除/禁用/无渠道时静默跳过。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public class AiAlertExplanationNotifier {

    private final AlertRuleMapper ruleMapper;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final Optional<JavaMailSender> mailSender;

    /** 注入规则访问、配置与外部通道客户端；不落任何通知状态行。 */
    public AiAlertExplanationNotifier(AlertRuleMapper ruleMapper, AppProperties appProperties,
            RestTemplate restTemplate, Optional<JavaMailSender> mailSender) {
        this.ruleMapper = ruleMapper;
        this.appProperties = appProperties;
        this.restTemplate = restTemplate;
        this.mailSender = mailSender;
    }

    /**
     * 尽力而为推送解释补充通知；任何异常只记日志，绝不向上抛出
     * （消费事务已提交，抛出只会制造无意义重试）。
     *
     * @param facts 告警触发事实（定位 rule 与 record）
     * @param explanation 已落库的解释结果
     */
    public void dispatchSupplement(AiAlertFacts facts, AiAlertExplanationVo explanation) {
        try {
            dispatch(facts, explanation);
        } catch (Exception exception) {
            log.warn("AI explanation supplement notification failed, recordId={}: {}",
                    facts.recordId(), exception.getMessage());
        }
    }

    /** 逐渠道推送；规则不可用或通知总开关关闭时跳过。 */
    private void dispatch(AiAlertFacts facts, AiAlertExplanationVo explanation) {
        if (!appProperties.getAlert().isNotificationEnabled()) {
            return;
        }
        AlertRuleEntity rule = ruleMapper.selectActiveRuleById(facts.ruleId());
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            log.info("explanation supplement skipped: rule unavailable, ruleId={}", facts.ruleId());
            return;
        }
        if (mailSender.isPresent() && StringUtils.hasText(rule.getNotifyEmail())) {
            sendEmail(rule, facts, explanation);
        }
        if (StringUtils.hasText(rule.getNotifyDingtalk())) {
            sendDingtalk(rule.getNotifyDingtalk(), explanation);
        }
        if (StringUtils.hasText(rule.getNotifyWebhook())) {
            sendWebhook(rule.getNotifyWebhook(), facts, explanation);
        }
    }

    /** 发送"AI 解释"补充邮件。 */
    private void sendEmail(AlertRuleEntity rule, AiAlertFacts facts, AiAlertExplanationVo explanation) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(appProperties.getAlert().getMailFrom());
        message.setTo(rule.getNotifyEmail().split(","));
        message.setSubject("[AI 解释] alert record #" + facts.recordId() + " explanation");
        message.setText(buildText(facts, explanation));
        mailSender.get().send(message);
    }

    /** 发送钉钉文本消息。 */
    private void sendDingtalk(String url, AiAlertExplanationVo explanation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("msgtype", "text");
        payload.put("text", Map.of("content", "[AI 解释] " + explanation.getSummary()));
        restTemplate.postForObject(url, payload, String.class);
    }

    /** 发送自定义 Webhook：结构化解释载荷（type 标识消息类别，供接收方路由）。 */
    private void sendWebhook(String url, AiAlertFacts facts, AiAlertExplanationVo explanation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ai.alert.explanation");
        payload.put("record_id", facts.recordId());
        payload.put("server_id", facts.serverId());
        payload.put("summary", explanation.getSummary());
        payload.put("possible_causes", explanation.getPossibleCauses());
        payload.put("impact", explanation.getImpact());
        payload.put("suggestions", explanation.getSuggestions());
        payload.put("limitations", explanation.getLimitations());
        restTemplate.postForObject(url, payload, String.class);
    }

    /** 组装纯文本正文：摘要 + 原因/影响/建议清单，全部来自已过审的模型输出。 */
    private String buildText(AiAlertFacts facts, AiAlertExplanationVo explanation) {
        StringBuilder text = new StringBuilder("[AI 解释] Alert record #").append(facts.recordId())
                .append(" (").append(facts.metric()).append('=').append(facts.currentValue())
                .append(", threshold ").append(facts.thresholdValue()).append(", ").append(facts.level())
                .append(")\n\n").append(explanation.getSummary()).append('\n');
        appendSection(text, "Possible causes", explanation.getPossibleCauses());
        appendSection(text, "Impact", explanation.getImpact());
        appendSection(text, "Suggestions", explanation.getSuggestions());
        appendSection(text, "Limitations", explanation.getLimitations());
        return text.toString();
    }

    /** 追加一个编号清单小节。 */
    private void appendSection(StringBuilder text, String title, java.util.List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        text.append('\n').append(title).append(":\n");
        for (int index = 0; index < items.size(); index++) {
            text.append(index + 1).append(". ").append(items.get(index)).append('\n');
        }
    }
}

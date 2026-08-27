package com.susumonitor.server.module.alert.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 通知文案按记录状态区分触发/恢复语义：恢复文案不携带触发值。
 */
class AlertMessageBuilderTests {

    private final AlertRuleEntity rule = rule();
    private final AlertRecordVo triggered = record("unread", null);
    private final AlertRecordVo resolved = record("resolved", OffsetDateTime.of(2026, 8, 15, 11, 30, 0, 0, ZoneOffset.UTC));

    /** 触发主题带当前值；恢复主题声明已恢复且不携带触发值。 */
    @Test
    void subjectDistinguishesResolvedFromTriggered() {
        String triggerSubject = AlertMessageBuilder.subject(rule, triggered);
        assertTrue(triggerSubject.startsWith("[告警] WARNING cpu"));
        assertTrue(triggerSubject.contains("当前 92.5"));

        String resolvedSubject = AlertMessageBuilder.subject(rule, resolved);
        assertTrue(resolvedSubject.startsWith("[恢复] WARNING cpu 已恢复"));
        assertFalse(resolvedSubject.contains("当前"));
    }

    /** 恢复正文列出触发/恢复时间且不含"当前值"行。 */
    @Test
    void resolvedBodyListsRecoveryTime() {
        String body = AlertMessageBuilder.body(rule, resolved);
        assertTrue(body.contains("告警已恢复"));
        assertTrue(body.contains("触发时间: 2026-08-15T10:00Z"));
        assertTrue(body.contains("恢复时间: 2026-08-15T11:30Z"));
        assertFalse(body.contains("当前值"));
    }

    /** 钉钉文本恢复语义：不携带触发值。 */
    @Test
    void dingtalkTextDistinguishesResolvedFromTriggered() {
        assertTrue(AlertMessageBuilder.dingtalkText(rule, triggered).startsWith("【SuSuMonitor 告警】"));
        String resolvedText = AlertMessageBuilder.dingtalkText(rule, resolved);
        assertTrue(resolvedText.startsWith("【SuSuMonitor 恢复】"));
        assertTrue(resolvedText.contains("已恢复"));
        assertFalse(resolvedText.contains("超过阈值"));
    }

    private AlertRuleEntity rule() {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(1L);
        rule.setMetric("cpu");
        rule.setThresholdValue(new BigDecimal("80"));
        rule.setLevel("warning");
        return rule;
    }

    private AlertRecordVo record(String status, OffsetDateTime resolvedAt) {
        AlertRecordVo record = new AlertRecordVo();
        record.setId(1L);
        record.setRuleId(1L);
        record.setServerId(9L);
        record.setMetric("cpu");
        record.setCurrentValue(new BigDecimal("92.5"));
        record.setThresholdValue(new BigDecimal("80.0"));
        record.setLevel("warning");
        record.setStatus(status);
        record.setMessage("cpu > 80 (current: 92.5)");
        record.setTriggeredAt(OffsetDateTime.of(2026, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC));
        record.setResolvedAt(resolvedAt);
        return record;
    }
}

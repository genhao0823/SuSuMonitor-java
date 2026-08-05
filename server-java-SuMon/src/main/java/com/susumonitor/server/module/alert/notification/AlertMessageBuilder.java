package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;

/**
 * 构建邮件主题/正文与钉钉机器人文本。
 *
 * <p>邮件与钉钉共用同一份告警摘要，字段顺序固定便于排查；
 * 数值以十进制字符串展示，避免 BigDecimal 的科学计数法。</p>
 */
final class AlertMessageBuilder {

    private AlertMessageBuilder() {
    }

    /** 邮件主题：如 "[告警] WARNING cpu 超过阈值（当前 92.30）"。 */
    static String subject(AlertRuleEntity rule, AlertRecordVo record) {
        return String.format("[告警] %s %s 超过阈值（当前 %s）",
                rule.getLevel().toUpperCase(), rule.getMetric(), plain(record.getCurrentValue()));
    }

    /** 邮件正文：按行列出服务器、指标、当前值、阈值、等级、触发时间与详情。 */
    static String body(AlertRuleEntity rule, AlertRecordVo record) {
        return String.format("服务器 #%d 触发告警%n"
                        + "指标: %s%n"
                        + "当前值: %s%n"
                        + "阈值: %s%n"
                        + "等级: %s%n"
                        + "触发时间: %s%n"
                        + "详情: %s",
                record.getServerId(), rule.getMetric(), plain(record.getCurrentValue()),
                plain(rule.getThresholdValue()), rule.getLevel(), record.getTriggeredAt(), record.getMessage());
    }

    /** 钉钉机器人 text 内容。 */
    static String dingtalkText(AlertRuleEntity rule, AlertRecordVo record) {
        return String.format("【SuSuMonitor 告警】服务器 #%d %s 超过阈值：当前 %s，阈值 %s（%s）",
                record.getServerId(), rule.getMetric(), plain(record.getCurrentValue()),
                plain(rule.getThresholdValue()), rule.getLevel());
    }

    /** 数值转十进制字符串，null 显示为空。 */
    private static String plain(java.math.BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}

package com.susumonitor.server.module.alert.notification;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;

/**
 * 构建邮件主题/正文与钉钉机器人文本。
 *
 * <p>邮件与钉钉共用同一份告警摘要，字段顺序固定便于排查；
 * 数值以十进制字符串展示，避免 BigDecimal 的科学计数法。
 * 按记录状态区分触发（[告警]）与恢复（[恢复]）语义——恢复文案不携带
 * current 值（record 中的 current_value 是触发时刻的值，不是恢复时刻的值）。</p>
 */
final class AlertMessageBuilder {

    private static final String RESOLVED_STATUS = "resolved";

    private AlertMessageBuilder() {
    }

    /** 邮件主题：触发 "[告警] ..."，恢复 "[恢复] ... 已恢复（阈值 xxx）"。 */
    static String subject(AlertRuleEntity rule, AlertRecordVo record) {
        if (RESOLVED_STATUS.equals(record.getStatus())) {
            return String.format("[恢复] %s %s 已恢复（阈值 %s）",
                    rule.getLevel().toUpperCase(), rule.getMetric(), plain(rule.getThresholdValue()));
        }
        return String.format("[告警] %s %s 超过阈值（当前 %s）",
                rule.getLevel().toUpperCase(), rule.getMetric(), plain(record.getCurrentValue()));
    }

    /** 邮件正文：触发按行列出服务器、指标、当前值、阈值、等级、触发时间与详情；恢复列出触发/恢复时间。 */
    static String body(AlertRuleEntity rule, AlertRecordVo record) {
        if (RESOLVED_STATUS.equals(record.getStatus())) {
            return String.format("服务器 #%d 告警已恢复%n"
                            + "指标: %s%n"
                            + "等级: %s%n"
                            + "触发时间: %s%n"
                            + "恢复时间: %s%n"
                            + "详情: %s",
                    record.getServerId(), rule.getMetric(), rule.getLevel(),
                    record.getTriggeredAt(), record.getResolvedAt(), record.getMessage());
        }
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

    /** 钉钉机器人 text 内容：触发 "[SuSuMonitor 告警]"，恢复 "[SuSuMonitor 恢复]"。 */
    static String dingtalkText(AlertRuleEntity rule, AlertRecordVo record) {
        if (RESOLVED_STATUS.equals(record.getStatus())) {
            return String.format("【SuSuMonitor 恢复】服务器 #%d %s 已恢复（%s）",
                    record.getServerId(), rule.getMetric(), rule.getLevel());
        }
        return String.format("【SuSuMonitor 告警】服务器 #%d %s 超过阈值：当前 %s，阈值 %s（%s）",
                record.getServerId(), rule.getMetric(), plain(record.getCurrentValue()),
                plain(rule.getThresholdValue()), rule.getLevel());
    }

    /** 数值转十进制字符串，null 显示为空。 */
    /** 将 BigDecimal 转换为十进制字符串，去除尾部零，null 时返回空字符串。 */
    private static String plain(java.math.BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}

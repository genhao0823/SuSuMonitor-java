package com.susumonitor.server.module.alert.enums;

import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import java.math.BigDecimal;

/**
 * 告警指标枚举，定义 alert_rules.metric 允许的值和对应的 MetricsLatestVo 字段。
 *
 * <p>枚举值与 alert_rules.metric 列的字符串存储值一致。extract 方法
 * 从 MetricsLatestVo 提取对应字段值，null 表示该指标在该采样中缺失
 * （如 Windows 的 temperature），null 值不触发告警。</p>
 */
public enum AlertMetric {
    /** CPU 使用率百分比。 */
    CPU("cpu"),
    /** 内存使用率百分比。 */
    MEMORY("memory"),
    /** 磁盘使用率百分比。 */
    DISK("disk"),
    /** 温度。 */
    TEMPERATURE("temperature"),
    /** 系统负载。 */
    LOAD("load");

    private final String ruleValue;

    /** 构造枚举值，绑定数据库列存储的字符串。 */
    AlertMetric(String ruleValue) {
        this.ruleValue = ruleValue;
    }

    /** 返回 alert_rules.metric 列存储的字符串值。 */
    public String ruleValue() {
        return ruleValue;
    }

    /**
     * 返回异步事件契约（message-contracts-v1）冻结的指标名。
     * 规则列使用 load，事件载荷按契约映射为 load_avg；其余指标两处同名。
     */
    public String eventValue() {
        return this == LOAD ? "load_avg" : ruleValue;
    }

    /**
     * 将规则 metric 字符串映射为事件契约指标名；未知值原样返回，由调用方校验。
     *
     * @param ruleValue alert_rules.metric 存储值
     * @return 事件契约指标名
     */
    public static String toEventMetric(String ruleValue) {
        AlertMetric metric = fromValue(ruleValue);
        return metric == null ? ruleValue : metric.eventValue();
    }

    /** 从 MetricsLatestVo 提取对应指标值，指标缺失时返回 null。 */
    public BigDecimal extract(MetricsLatestVo metrics) {
        if (metrics == null) {
            return null;
        }
        return switch (this) {
            case CPU -> metrics.getCpuPercent();
            case MEMORY -> metrics.getMemoryPercent();
            case DISK -> metrics.getDiskPercent();
            case TEMPERATURE -> metrics.getTemperature();
            case LOAD -> metrics.getLoadAvg();
        };
    }

    /** 将字符串安全转换为枚举，非法值返回 null。 */
    public static AlertMetric fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AlertMetric metric : values()) {
            if (metric.ruleValue.equals(value)) {
                return metric;
            }
        }
        return null;
    }
}

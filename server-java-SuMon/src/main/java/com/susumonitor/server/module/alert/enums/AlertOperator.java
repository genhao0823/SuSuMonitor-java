package com.susumonitor.server.module.alert.enums;

import java.math.BigDecimal;

/**
 * 告警比较操作符枚举，定义 alert_rules.operator 允许的值。
 *
 * <p>使用 BigDecimal.compareTo 比较，不考虑精度差异
 * （2.0 与 2.00 视为相等）。current 为 null 时返回 false，
 * 表示指标缺失不触发告警。</p>
 */
public enum AlertOperator {
    /** 大于。 */
    GT(">"),
    /** 大于等于。 */
    GE(">="),
    /** 小于。 */
    LT("<"),
    /** 小于等于。 */
    LE("<=");

    private final String ruleValue;

    /** 构造枚举值，绑定数据库列存储的字符串。 */
    AlertOperator(String ruleValue) {
        this.ruleValue = ruleValue;
    }

    /** 返回 alert_rules.operator 列存储的字符串值。 */
    public String ruleValue() {
        return ruleValue;
    }

    /** 评估当前值是否满足操作符与阈值的关系，current 为 null 时返回 false。 */
    public boolean eval(BigDecimal current, BigDecimal threshold) {
        if (current == null || threshold == null) {
            return false;
        }
        int cmp = current.compareTo(threshold);
        return switch (this) {
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
        };
    }

    /** 将字符串安全转换为枚举，非法值返回 null。 */
    public static AlertOperator fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AlertOperator operator : values()) {
            if (operator.ruleValue.equals(value)) {
                return operator;
            }
        }
        return null;
    }
}

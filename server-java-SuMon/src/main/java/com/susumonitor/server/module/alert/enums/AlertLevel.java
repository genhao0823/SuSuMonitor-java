package com.susumonitor.server.module.alert.enums;

/**
 * 告警等级枚举，定义 alert_rules.level 和 alert_records.level 允许的值。
 */
public enum AlertLevel {
    /** 警告等级。 */
    WARNING("warning"),
    /** 严重等级。 */
    CRITICAL("critical");

    private final String ruleValue;

    /** 构造枚举值，绑定数据库列存储的字符串。 */
    AlertLevel(String ruleValue) {
        this.ruleValue = ruleValue;
    }

    /** 返回数据库列存储的字符串值。 */
    public String ruleValue() {
        return ruleValue;
    }

    /** 将字符串安全转换为枚举，非法值返回 null。 */
    public static AlertLevel fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AlertLevel level : values()) {
            if (level.ruleValue.equals(value)) {
                return level;
            }
        }
        return null;
    }
}

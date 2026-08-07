package com.susumonitor.server.module.alert.enums;

/**
 * 告警记录状态枚举，定义 alert_records.status 允许的值。
 *
 * <p>状态迁移：unread → read（用户标记已读），
 * unread/read → resolved（告警恢复时自动标记）。</p>
 */
public enum AlertRecordStatus {
    /** 未读。 */
    UNREAD("unread"),
    /** 已读。 */
    READ("read"),
    /** 已恢复。 */
    RESOLVED("resolved");

    private final String ruleValue;

    /** 构造枚举值，绑定数据库列存储的字符串。 */
    AlertRecordStatus(String ruleValue) {
        this.ruleValue = ruleValue;
    }

    /** 返回数据库列存储的字符串值。 */
    public String ruleValue() {
        return ruleValue;
    }

    /** 将字符串安全转换为枚举，非法值返回 null。 */
    public static AlertRecordStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AlertRecordStatus status : values()) {
            if (status.ruleValue.equals(value)) {
                return status;
            }
        }
        return null;
    }
}

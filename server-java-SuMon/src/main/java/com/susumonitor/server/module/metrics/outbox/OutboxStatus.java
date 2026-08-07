package com.susumonitor.server.module.metrics.outbox;

/**
 * Outbox 行状态枚举，定义 message_outbox.status 允许的值。
 *
 * <p>发布侧只有两态：pending（待发布/退避重试中）与 published（Broker 已确认）。
 * 不设 failed 状态——Outbox 语义是"Broker 恢复后必须补发"，失败仅通过退避留痕。</p>
 */
public enum OutboxStatus {
    /** 待发布（含退避重试等待中）。 */
    PENDING("pending"),
    /** Broker 已确认发布。 */
    PUBLISHED("published");

    private final String ruleValue;

    /**
     * 构造枚举常量。
     *
     * @param ruleValue 数据库列存储的字符串值
     */
    OutboxStatus(String ruleValue) {
        this.ruleValue = ruleValue;
    }

    /** 返回数据库列存储的字符串值。 */
    public String ruleValue() {
        return ruleValue;
    }

    /** 将字符串安全转换为枚举，非法值返回 null。 */
    public static OutboxStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (OutboxStatus status : values()) {
            if (status.ruleValue.equals(value)) {
                return status;
            }
        }
        return null;
    }
}

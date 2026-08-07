package com.susumonitor.server.module.alert.consume;

/**
 * 消费幂等记录状态枚举，定义 message_consume_records.status 允许的值。
 *
 * <p>consumed=业务已成功处理；failed=重试耗尽后的失败留痕（消息进入 DLQ）。</p>
 */
public enum ConsumeStatus {
    /** 已成功消费（业务与记录同事务提交）。 */
    CONSUMED("consumed"),
    /** 失败留痕（重试耗尽，消息进入 DLQ）。 */
    FAILED("failed");

    private final String ruleValue;

    /** 构造枚举值，绑定数据库列存储的字符串。 */
    ConsumeStatus(String ruleValue) {
        this.ruleValue = ruleValue;
    }

    /** 返回数据库列存储的字符串值。 */
    public String ruleValue() {
        return ruleValue;
    }

    /** 将字符串安全转换为枚举，非法值返回 null。 */
    public static ConsumeStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ConsumeStatus status : values()) {
            if (status.ruleValue.equals(value)) {
                return status;
            }
        }
        return null;
    }
}

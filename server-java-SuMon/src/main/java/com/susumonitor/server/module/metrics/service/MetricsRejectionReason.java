package com.susumonitor.server.module.metrics.service;

/**
 * 枚举 metrics 数据入口的永久拒绝原因，Agent 可安全地将此类消息死信处理而非无限重试。
 */
public enum MetricsRejectionReason {
    /** 指标载荷格式或字段值非法。 */
    INVALID_METRICS_PAYLOAD("invalid_metrics_payload"),
    /** 采集时间不晚于最后一次接受的采样时间。 */
    STALE_COLLECTED_AT("stale_collected_at"),
    /** 认证服务器 ID 在 servers 表中不存在或已删除。 */
    SERVER_NOT_FOUND("server_not_found");

    private final String value;

    /**
     * 构造拒绝原因枚举常量。
     *
     * @param value 线缆协议传递的稳定字符串值
     */
    MetricsRejectionReason(String value) {
        this.value = value;
    }

    /** Returns the stable wire value carried by metrics.nack. */
    public String value() {
        return value;
    }
}

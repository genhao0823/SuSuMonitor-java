package com.susumonitor.server.module.metrics.service;

/**
 * 枚举 metrics 数据入口的拒绝原因。
 *
 * <p>原因分两级：永久拒绝（Agent 可安全死信而不重试）与可重试拒绝
 * （Agent 有限重试后仍未成功再死信）。</p>
 */
public enum MetricsRejectionReason {
    /** 指标载荷格式或字段值非法（永久）。 */
    INVALID_METRICS_PAYLOAD("invalid_metrics_payload"),
    /** 采集时间不晚于最后一次接受的采样时间（永久）。 */
    STALE_COLLECTED_AT("stale_collected_at"),
    /** 认证服务器 ID 在 servers 表中不存在或已删除（永久）。 */
    SERVER_NOT_FOUND("server_not_found"),
    /** 入库阶段可恢复故障（数据库连接/锁等临时错误，可重试）。 */
    RETRIABLE_SERVER_ERROR("retriable_server_error");

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

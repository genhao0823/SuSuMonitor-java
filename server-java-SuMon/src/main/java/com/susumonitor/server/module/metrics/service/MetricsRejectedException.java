package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;

/**
 * 表示关联的 metrics.report 被拒绝，允许 WebSocket 适配器返回 metrics.nack 并携带稳定原因。
 *
 * <p>原因分永久拒绝（Agent 死信不重试）与可重试拒绝（Agent 有限重试后死信）。</p>
 */
public class MetricsRejectedException extends BusinessException {

    private final MetricsRejectionReason reason;

    /**
     * 构造拒绝异常，按拒绝原因映射 HTTP 错误码。
     *
     * @param reason 拒绝原因分类
     */
    public MetricsRejectedException(MetricsRejectionReason reason) {
        super(switch (reason) {
            case SERVER_NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case RETRIABLE_SERVER_ERROR -> ErrorCode.DATABASE_ERROR;
            default -> ErrorCode.INVALID_REQUEST_PARAMETER;
        });
        this.reason = reason;
    }

    /** Returns the machine-readable rejection classification. */
    public MetricsRejectionReason getReason() {
        return reason;
    }
}

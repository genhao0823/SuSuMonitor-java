package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;

/**
 * 表示关联的 metrics.report 永久无效，允许 WebSocket 适配器返回 metrics.nack 并携带稳定原因。
 */
public class MetricsRejectedException extends BusinessException {

    private final MetricsRejectionReason reason;

    /**
     * 构造永久拒绝异常，按拒绝原因映射 HTTP 错误码。
     *
     * @param reason 永久拒绝原因分类
     */
    public MetricsRejectedException(MetricsRejectionReason reason) {
        super(reason == MetricsRejectionReason.SERVER_NOT_FOUND
                ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.INVALID_REQUEST_PARAMETER);
        this.reason = reason;
    }

    /** Returns the machine-readable permanent rejection classification. */
    public MetricsRejectionReason getReason() {
        return reason;
    }
}

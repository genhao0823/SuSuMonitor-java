package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;

/**
 * Signals that a correlated metrics.report is permanently invalid unchanged,
 * allowing the WebSocket adapter to return metrics.nack with a stable reason.
 */
public class MetricsRejectedException extends BusinessException {

    private final MetricsRejectionReason reason;

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

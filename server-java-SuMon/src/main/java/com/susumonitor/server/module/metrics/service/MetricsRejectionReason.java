package com.susumonitor.server.module.metrics.service;

/**
 * Enumerates permanent metrics ingress rejection reasons that an Agent can
 * safely dead-letter instead of retrying the unchanged report indefinitely.
 */
public enum MetricsRejectionReason {
    INVALID_METRICS_PAYLOAD("invalid_metrics_payload"),
    STALE_COLLECTED_AT("stale_collected_at"),
    SERVER_NOT_FOUND("server_not_found");

    private final String value;

    MetricsRejectionReason(String value) {
        this.value = value;
    }

    /** Returns the stable wire value carried by metrics.nack. */
    public String value() {
        return value;
    }
}

package com.susumonitor.server.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * Agent 心跳携带的可选投递遥测统计。
 *
 * <p>字段与 Agent metricbuffer.Stats 一一对应；老版本 Agent 不携带任何字段，
 * 此时 {@link #hasDeliveryStats()} 返回 false，服务端保持原统计不变。
 */
public record AgentHeartbeatPayload(
        @JsonProperty("pending_count") Long pendingCount,
        @JsonProperty("pending_bytes") Long pendingBytes,
        @JsonProperty("oldest_collected_at") OffsetDateTime oldestCollectedAt,
        @JsonProperty("drop_count") Long dropCount,
        @JsonProperty("dead_letter_count") Long deadLetterCount,
        @JsonProperty("dead_letter_bytes") Long deadLetterBytes) {

    /** 是否携带至少一个投递遥测字段。 */
    public boolean hasDeliveryStats() {
        return pendingCount != null || pendingBytes != null || oldestCollectedAt != null
                || dropCount != null || deadLetterCount != null || deadLetterBytes != null;
    }
}

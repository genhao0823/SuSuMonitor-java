package com.susumonitor.server.module.alert.consume;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * alert.resolved.v1 消息信封（message-contracts-v1.md §二/§五）。
 *
 * <p>字段与 {@code AlertResolvedEnvelopeFactory} 输出的 JSON 一一对应
 * （snake_case），时间字段以 String 承载，格式校验在
 * {@link AlertResolvedMessageValidator} 中执行。</p>
 */
public record AlertResolvedMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("occurred_at") String occurredAt,
        String producer,
        Payload payload) {

    /** alert.resolved.v1 载荷：恢复语义冻结的 8 个字段（不含触发值）。 */
    public record Payload(
            @JsonProperty("server_id") Long serverId,
            @JsonProperty("rule_id") Long ruleId,
            @JsonProperty("record_id") Long recordId,
            String metric,
            String level,
            String status,
            @JsonProperty("triggered_at") String triggeredAt,
            @JsonProperty("resolved_at") String resolvedAt) {
    }
}

package com.susumonitor.server.module.ai.consume;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * ai.alert.explanation.requested.v1 消息信封（message-contracts-v1.md §二/§六）。
 *
 * <p>字段与 {@code AiAlertExplanationEnvelopeFactory} 输出的 JSON 一一对应
 * （snake_case），时间字段以 String 承载，格式校验在
 * {@link AiAlertExplanationMessageValidator} 中执行。</p>
 */
public record AiAlertExplanationMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("occurred_at") String occurredAt,
        String producer,
        Payload payload) {

    /** ai.alert.explanation.requested.v1 载荷：契约冻结的 8 个白名单字段。 */
    public record Payload(
            @JsonProperty("server_id") Long serverId,
            @JsonProperty("rule_id") Long ruleId,
            @JsonProperty("record_id") Long recordId,
            String metric,
            @JsonProperty("current_value") BigDecimal currentValue,
            @JsonProperty("threshold_value") BigDecimal thresholdValue,
            String level,
            @JsonProperty("triggered_at") String triggeredAt) {
    }
}

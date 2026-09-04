package com.susumonitor.server.module.ai.consume;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 验证 ai.alert.explanation.requested.v1 字段级契约校验：畸形载荷在进入业务前被拒绝。 */
class AiAlertExplanationMessageValidatorTests {

    private static final String EVENT_ID = "d1a7c3e0-4f8b-4c2a-9b6d-2e5f8a1c7b3d";

    private final AiAlertExplanationMessageValidator validator = new AiAlertExplanationMessageValidator();

    /** 合法信封通过校验。 */
    @Test
    void shouldAcceptValidEnvelope() {
        assertDoesNotThrow(() -> validator.validate(
                message(EVENT_ID, "ai.alert.explanation.requested", 1, "ai-service",
                        "2026-09-04T12:00:06Z", validPayload("2026-09-04T12:00:05Z"))));
    }

    /** event_id 非法 UUID 被拒绝。 */
    @Test
    void shouldRejectInvalidEventId() {
        AiAlertExplanationMessage message = message("not-a-uuid", "ai.alert.explanation.requested", 1,
                "ai-service", "2026-09-04T12:00:06Z", validPayload("2026-09-04T12:00:05Z"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** schema_version 与 event_type 由消费者 parseEnvelope 层校验（与 alert 契约消费同构），
     * 本校验器只覆盖信封身份与载荷不变量；此处验证非法 producer。 */
    @Test
    void shouldRejectWrongProducer() {
        AiAlertExplanationMessage message = message(EVENT_ID, "ai.alert.explanation.requested", 1,
                "alert-service", "2026-09-04T12:00:06Z", validPayload("2026-09-04T12:00:05Z"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** occurred_at 非 UTC 被拒绝。 */
    @Test
    void shouldRejectNonUtcOccurredAt() {
        AiAlertExplanationMessage message = message(EVENT_ID, "ai.alert.explanation.requested", 1,
                "ai-service", "2026-09-04T20:00:06+08:00", validPayload("2026-09-04T12:00:05Z"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 载荷缺 triggered_at 被拒绝。 */
    @Test
    void shouldRejectPayloadWithoutTriggeredAt() {
        AiAlertExplanationMessage message = message(EVENT_ID, "ai.alert.explanation.requested", 1,
                "ai-service", "2026-09-04T12:00:06Z", validPayload(null));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 载荷指标名不在冻结集合内被拒绝。 */
    @Test
    void shouldRejectUnknownMetric() {
        AiAlertExplanationMessage.Payload valid = validPayload("2026-09-04T12:00:05Z");
        AiAlertExplanationMessage message = message(EVENT_ID, "ai.alert.explanation.requested", 1,
                "ai-service", "2026-09-04T12:00:06Z",
                new AiAlertExplanationMessage.Payload(valid.serverId(), valid.ruleId(), valid.recordId(),
                        "gpu", valid.currentValue(), valid.thresholdValue(), valid.level(), valid.triggeredAt()));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 载荷非法身份（非正整数 ID）被拒绝。 */
    @Test
    void shouldRejectNonPositiveIdentity() {
        AiAlertExplanationMessage.Payload valid = validPayload("2026-09-04T12:00:05Z");
        AiAlertExplanationMessage message = message(EVENT_ID, "ai.alert.explanation.requested", 1,
                "ai-service", "2026-09-04T12:00:06Z",
                new AiAlertExplanationMessage.Payload(0L, valid.ruleId(), valid.recordId(),
                        valid.metric(), valid.currentValue(), valid.thresholdValue(), valid.level(),
                        valid.triggeredAt()));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 载荷缺 current_value 被拒绝。 */
    @Test
    void shouldRejectPayloadWithoutCurrentValue() {
        AiAlertExplanationMessage message = message(EVENT_ID, "ai.alert.explanation.requested", 1,
                "ai-service", "2026-09-04T12:00:06Z",
                new AiAlertExplanationMessage.Payload(123L, 456L, 789L, "cpu",
                        null, new BigDecimal("80.0"), "warning", "2026-09-04T12:00:05Z"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** null 消息被拒绝。 */
    @Test
    void shouldRejectNullMessage() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
    }

    private AiAlertExplanationMessage message(String eventId, String eventType, int schemaVersion,
            String producer, String occurredAt, AiAlertExplanationMessage.Payload payload) {
        return new AiAlertExplanationMessage(eventId, eventType, schemaVersion, occurredAt, producer, payload);
    }

    private AiAlertExplanationMessage.Payload validPayload(String triggeredAt) {
        return new AiAlertExplanationMessage.Payload(123L, 456L, 789L, "cpu",
                new BigDecimal("92.5"), new BigDecimal("80.0"), "warning", triggeredAt);
    }
}

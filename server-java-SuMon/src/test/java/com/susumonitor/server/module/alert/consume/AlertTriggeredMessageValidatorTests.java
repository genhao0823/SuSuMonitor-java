package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * AlertTriggeredMessageValidator 字段级契约校验测试。
 */
class AlertTriggeredMessageValidatorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AlertTriggeredMessageValidator validator = new AlertTriggeredMessageValidator();

    /** 契约 §四 合法信封应通过校验。 */
    @Test
    void validEnvelopePasses() throws Exception {
        assertDoesNotThrow(() -> validator.validate(parse(envelopeJson())));
    }

    /** 信封 event_id 非法 UUID 拒绝。 */
    @Test
    void invalidEventIdRejected() throws Exception {
        AlertTriggeredMessage message = parse(envelopeJson().replace(
                "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0", "not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 信封 producer 与契约不符拒绝。 */
    @Test
    void invalidProducerRejected() throws Exception {
        AlertTriggeredMessage message = parse(envelopeJson().replace("\"alert-service\"", "\"other-service\""));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 非 UTC 时间偏移的 occurred_at 拒绝。 */
    @Test
    void nonUtcOccurredAtRejected() throws Exception {
        AlertTriggeredMessage message = parse(envelopeJson().replace(
                "\"occurred_at\": \"2026-07-28T12:00:05Z\"", "\"occurred_at\": \"2026-07-28T20:00:05+08:00\""));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 载荷身份字段非正数拒绝。 */
    @Test
    void nonPositiveIdentityRejected() throws Exception {
        AlertTriggeredMessage message = parse(envelopeJson().replace("\"server_id\": 123,", "\"server_id\": 0,"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 非冻结指标名拒绝。 */
    @Test
    void unknownMetricRejected() throws Exception {
        AlertTriggeredMessage message = parse(envelopeJson().replace("\"metric\": \"cpu\"", "\"metric\": \"gpu\""));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 载荷数值为空拒绝。 */
    @Test
    void nullValueRejected() throws Exception {
        AlertTriggeredMessage message = parse(envelopeJson().replace(
                "\"current_value\": 92.5", "\"current_value\": null"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    /** 非 UTC 时间偏移的 triggered_at 拒绝。 */
    @Test
    void nonUtcTriggeredAtRejected() throws Exception {
        AlertTriggeredMessage message = parse(envelopeJson().replace(
                "\"triggered_at\": \"2026-07-28T12:00:05Z\"", "\"triggered_at\": \"2026-07-28T12:00:05+09:00\""));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message));
    }

    private AlertTriggeredMessage parse(String json) throws Exception {
        return objectMapper.readValue(json, AlertTriggeredMessage.class);
    }

    private String envelopeJson() {
        return """
                {
                  "event_id": "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0",
                  "event_type": "alert.triggered",
                  "schema_version": 1,
                  "occurred_at": "2026-07-28T12:00:05Z",
                  "producer": "alert-service",
                  "payload": {
                    "server_id": 123,
                    "rule_id": 456,
                    "record_id": 789,
                    "metric": "cpu",
                    "current_value": 92.5,
                    "threshold_value": 80.0,
                    "level": "warning",
                    "status": "unread",
                    "triggered_at": "2026-07-28T12:00:05Z"
                  }
                }
                """;
    }
}
package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 验证 alert.triggered.v1 消息信封按冻结契约（message-contracts-v1.md §四）反序列化。
 */
class AlertTriggeredMessageTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 契约示例信封可完整反序列化，字段与契约 §四 一致（snake_case、时间字符串）。 */
    @Test
    void contractEnvelopeShouldDeserialize() throws Exception {
        String envelope = """
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

        AlertTriggeredMessage message = objectMapper.readValue(envelope, AlertTriggeredMessage.class);

        assertEquals("ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0", message.eventId());
        assertEquals("alert.triggered", message.eventType());
        assertEquals(1, message.schemaVersion());
        assertEquals("2026-07-28T12:00:05Z", message.occurredAt());
        assertEquals("alert-service", message.producer());

        AlertTriggeredMessage.Payload payload = message.payload();
        assertNotNull(payload);
        assertEquals(123L, payload.serverId());
        assertEquals(456L, payload.ruleId());
        assertEquals(789L, payload.recordId());
        assertEquals("cpu", payload.metric());
        assertEquals(0, new BigDecimal("92.5").compareTo(payload.currentValue()));
        assertEquals(0, new BigDecimal("80.0").compareTo(payload.thresholdValue()));
        assertEquals("warning", payload.level());
        assertEquals("unread", payload.status());
        assertEquals("2026-07-28T12:00:05Z", payload.triggeredAt());
    }
}
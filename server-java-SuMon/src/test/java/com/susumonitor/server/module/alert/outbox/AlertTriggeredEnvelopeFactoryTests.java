package com.susumonitor.server.module.alert.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 验证冻结信封（message-contracts-v1.md §二/§四）与实现序列化结果一致。
 */
class AlertTriggeredEnvelopeFactoryTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-28T12:00:05Z"), ZoneOffset.UTC);
    private static final String EVENT_ID = "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0";

    private final AlertTriggeredEnvelopeFactory factory =
            new AlertTriggeredEnvelopeFactory(new ObjectMapper(), CLOCK);

    /** 信封头字段与契约 §二 一致（event_id/event_type/schema_version/occurred_at/producer/payload）。 */
    @Test
    void envelopeHeaderShouldMatchContract() throws Exception {
        JsonNode envelope = new ObjectMapper().readTree(factory.build(record(), EVENT_ID));

        assertEquals(EVENT_ID, envelope.get("event_id").asText());
        assertEquals("alert.triggered", envelope.get("event_type").asText());
        assertEquals(1, envelope.get("schema_version").asInt());
        assertEquals("2026-07-28T12:00:05Z", envelope.get("occurred_at").asText());
        assertEquals("alert-service", envelope.get("producer").asText());
        assertTrue(envelope.has("payload"));
        // 可选字段本阶段不携带。
        assertNull(envelope.get("trace_id"));
        assertNull(envelope.get("correlation_id"));
    }

    /** payload 字段与契约 §四 一致（snake_case、UTC 时间、冻结字段完整）。 */
    @Test
    void payloadShouldMatchContract() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(factory.build(record(), EVENT_ID)).get("payload");

        assertEquals(123L, payload.get("server_id").asLong());
        assertEquals(456L, payload.get("rule_id").asLong());
        assertEquals(789L, payload.get("record_id").asLong());
        assertEquals("cpu", payload.get("metric").asText());
        assertEquals(0, new BigDecimal("92.5").compareTo(payload.get("current_value").decimalValue()));
        assertEquals(0, new BigDecimal("80.0").compareTo(payload.get("threshold_value").decimalValue()));
        assertEquals("warning", payload.get("level").asText());
        assertEquals("unread", payload.get("status").asText());
        assertEquals("2026-07-28T12:00:05Z", payload.get("triggered_at").asText());
        // 载荷不携带展示字段 message（契约 §四 未冻结）。
        assertNull(payload.get("message"));
    }

    /** 契约常量与 payload 语义一致，供发布/消费两侧复用。 */
    @Test
    void contractConstantsShouldBeStable() {
        assertEquals("alert.triggered", AlertTriggeredEnvelopeFactory.EVENT_TYPE);
        assertEquals("alert.triggered.v1", AlertTriggeredEnvelopeFactory.ROUTING_KEY);
        assertEquals(1, AlertTriggeredEnvelopeFactory.SCHEMA_VERSION);
    }

    private AlertRecordVo record() {
        AlertRecordVo record = new AlertRecordVo();
        record.setId(789L);
        record.setRuleId(456L);
        record.setServerId(123L);
        record.setMetric("cpu");
        record.setCurrentValue(new BigDecimal("92.5"));
        record.setThresholdValue(new BigDecimal("80.0"));
        record.setLevel("warning");
        record.setStatus("unread");
        record.setMessage("cpu > 80.0 (current: 92.5)");
        record.setTriggeredAt(OffsetDateTime.of(2026, 7, 28, 12, 0, 5, 0, ZoneOffset.UTC));
        return record;
    }
}

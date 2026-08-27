package com.susumonitor.server.module.alert.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 验证恢复事件冻结信封（message-contracts-v1.md §二/§五）与实现序列化结果一致。
 */
class AlertResolvedEnvelopeFactoryTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-15T12:00:05Z"), ZoneOffset.UTC);
    private static final String EVENT_ID = "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0";

    private final AlertResolvedEnvelopeFactory factory =
            new AlertResolvedEnvelopeFactory(new ObjectMapper(), CLOCK);

    /** 信封头字段与契约 §二 一致（event_id/event_type/schema_version/occurred_at/producer/payload）。 */
    @Test
    void envelopeHeaderShouldMatchContract() throws Exception {
        JsonNode envelope = new ObjectMapper().readTree(factory.build(record(), EVENT_ID));

        assertEquals(EVENT_ID, envelope.get("event_id").asText());
        assertEquals("alert.resolved", envelope.get("event_type").asText());
        assertEquals(1, envelope.get("schema_version").asInt());
        assertEquals("2026-08-15T12:00:05Z", envelope.get("occurred_at").asText());
        assertEquals("alert-service", envelope.get("producer").asText());
        assertTrue(envelope.has("payload"));
        // 可选字段本阶段不携带。
        assertNull(envelope.get("trace_id"));
        assertNull(envelope.get("correlation_id"));
    }

    /** payload 字段与契约 §五 一致（snake_case、UTC 时间、恢复语义 8 字段、不携带触发值）。 */
    @Test
    void payloadShouldMatchContract() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(factory.build(record(), EVENT_ID)).get("payload");

        assertEquals(123L, payload.get("server_id").asLong());
        assertEquals(456L, payload.get("rule_id").asLong());
        assertEquals(789L, payload.get("record_id").asLong());
        assertEquals("cpu", payload.get("metric").asText());
        assertEquals("warning", payload.get("level").asText());
        assertEquals("resolved", payload.get("status").asText());
        assertEquals("2026-08-15T10:00:00Z", payload.get("triggered_at").asText());
        assertEquals("2026-08-15T11:30:00Z", payload.get("resolved_at").asText());
        // 恢复事件不携带触发值（record.currentValue 为触发时刻的值，非恢复语义字段）。
        assertNull(payload.get("current_value"));
        assertNull(payload.get("threshold_value"));
        assertNull(payload.get("message"));
    }

    /** 契约常量与 payload 语义一致，供发布/消费两侧复用。 */
    @Test
    void contractConstantsShouldBeStable() {
        assertEquals("alert.resolved", AlertResolvedEnvelopeFactory.EVENT_TYPE);
        assertEquals("alert.resolved.v1", AlertResolvedEnvelopeFactory.ROUTING_KEY);
        assertEquals(1, AlertResolvedEnvelopeFactory.SCHEMA_VERSION);
    }

    private AlertRecordVo record() {
        AlertRecordVo record = new AlertRecordVo();
        record.setId(789L);
        record.setRuleId(456L);
        record.setServerId(123L);
        record.setMetric("cpu");
        record.setLevel("warning");
        record.setStatus("resolved");
        record.setTriggeredAt(OffsetDateTime.of(2026, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC));
        record.setResolvedAt(OffsetDateTime.of(2026, 8, 15, 11, 30, 0, 0, ZoneOffset.UTC));
        return record;
    }
}

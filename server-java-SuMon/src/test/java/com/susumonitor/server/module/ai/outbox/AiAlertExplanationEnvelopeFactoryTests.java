package com.susumonitor.server.module.ai.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证 ai.alert.explanation.requested.v1 信封与冻结契约逐字段一致（message-contracts-v1.md §六）。 */
class AiAlertExplanationEnvelopeFactoryTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-04T12:00:07Z"), ZoneOffset.UTC);

    private final AiAlertExplanationEnvelopeFactory factory =
            new AiAlertExplanationEnvelopeFactory(objectMapper, clock);

    /** 信封字段与契约 §六 示例一致：event_type/schema_version/producer/UTC 秒级时间。 */
    @Test
    void shouldBuildEnvelopeMatchingFrozenContract() throws Exception {
        String json = factory.build(record("cpu"), UUID.randomUUID().toString());
        JsonNode envelope = objectMapper.readTree(json);

        assertEquals("ai.alert.explanation.requested", envelope.path("event_type").asText());
        assertEquals(1, envelope.path("schema_version").asInt());
        assertEquals("ai-service", envelope.path("producer").asText());
        assertEquals("2026-09-04T12:00:07Z", envelope.path("occurred_at").asText());
        assertTrue(isUuid(envelope.path("event_id").asText()));
    }

    /** 载荷只携带契约冻结的 8 个白名单字段，不携带 message/status 等展示字段。 */
    @Test
    void shouldCarryOnlyFrozenWhitelistPayloadFields() throws Exception {
        String json = factory.build(record("cpu"), UUID.randomUUID().toString());
        JsonNode payload = objectMapper.readTree(json).path("payload");

        assertEquals(123L, payload.path("server_id").asLong());
        assertEquals(456L, payload.path("rule_id").asLong());
        assertEquals(789L, payload.path("record_id").asLong());
        assertEquals("cpu", payload.path("metric").asText());
        assertEquals(0, new java.math.BigDecimal("92.5")
                .compareTo(payload.path("current_value").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("80.0")
                .compareTo(payload.path("threshold_value").decimalValue()));
        assertEquals("warning", payload.path("level").asText());
        assertEquals("2026-09-04T11:55:00Z", payload.path("triggered_at").asText());
        assertFalse(payload.has("status"));
        assertFalse(payload.has("message"));
        assertEquals(8, payload.size());
    }

    /** load 规则值映射为契约指标名 load_avg（与 alert.triggered.v1 同口径）。 */
    @Test
    void shouldMapLoadRuleMetricToLoadAvg() throws Exception {
        String json = factory.build(record("load"), UUID.randomUUID().toString());

        assertEquals("load_avg", objectMapper.readTree(json).path("payload").path("metric").asText());
    }

    private AlertRecordVo record(String metric) {
        AlertRecordVo vo = new AlertRecordVo();
        vo.setId(789L);
        vo.setRuleId(456L);
        vo.setServerId(123L);
        vo.setMetric(metric);
        vo.setCurrentValue(new java.math.BigDecimal("92.5"));
        vo.setThresholdValue(new java.math.BigDecimal("80.0"));
        vo.setLevel("warning");
        vo.setStatus("unread");
        vo.setMessage("CPU is high");
        vo.setTriggeredAt(OffsetDateTime.of(2026, 9, 4, 11, 55, 0, 0, ZoneOffset.UTC));
        return vo;
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

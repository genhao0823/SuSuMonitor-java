package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.alert.consume.MetricsReportedMessage.Payload;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 校验 metrics.reported.v1 文档化 JSON Schema 与消费侧校验器的一致性。
 *
 * <p>schema 权威源位于 docs-SuMon/Protocol-SuMon/message-contracts-v1.schema.json；
 * 本测试以零依赖方式（Jackson 读取 + 手工对照）确保：
 * 1) 文档 schema 可解析且信封/载荷 required 与 {@link MetricsReportedMessageValidator} 一致；
 * 2) 合法样例序列化后满足 schema 声明的枚举/必填；缺失必填字段的样例不满足。</p>
 */
class MetricsReportedMessageSchemaTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 从仓库 docs 目录读取 schema（surefire cwd 为 server-java-SuMon）。 */
    private JsonNode schema() throws Exception {
        Path path = Path.of("..", "docs-SuMon", "Protocol-SuMon", "message-contracts-v1.schema.json");
        assertTrue(Files.exists(path), "schema file not found at " + path.toAbsolutePath());
        return MAPPER.readTree(Files.readAllBytes(path));
    }

    private static List<String> stringValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private MetricsReportedMessage validMessage() {
        Payload payload = new Payload(1L, "11111111-1111-4111-8111-111111111111",
                "2026-08-05T00:00:00Z", new BigDecimal("35.5"), new BigDecimal("62.1"),
                4096L, 8192L, new BigDecimal("48.0"), 10L, 50L, 100L, 200L,
                null, new BigDecimal("0.5"));
        return new MetricsReportedMessage("22222222-2222-4222-8222-222222222222",
                "metrics.reported", 1, "2026-08-05T00:00:00Z", "metrics-service", payload);
    }

    @Test
    void schemaShouldBeParsableWithExpectedEnvelope() throws Exception {
        JsonNode schema = schema();
        assertEquals("message-contracts-v1.schema.json", schema.path("$id").asText());
        assertEquals("metrics.reported", schema.path("properties").path("event_type").path("enum").get(0).asText());
        assertEquals(1, schema.path("properties").path("schema_version").path("const").asInt());
        assertEquals("metrics-service", schema.path("properties").path("producer").path("const").asText());
    }

    /** 文档 schema 的信封/载荷必填字段应与校验器实际检查一致。 */
    @Test
    void schemaRequiredFieldsShouldMatchValidator() throws Exception {
        JsonNode schema = schema();
        List<String> envelopeRequired = stringValues(schema.path("required"));
        assertEquals(List.of("event_id", "event_type", "schema_version", "occurred_at", "producer", "payload"),
                envelopeRequired);

        JsonNode payload = schema.path("properties").path("payload");
        List<String> payloadRequired = stringValues(payload.path("required"));
        // 校验器对每个字段都强制非空（percent/用量必填），temperature/load_avg 允许 null。
        assertTrue(payloadRequired.containsAll(List.of("server_id", "message_id", "collected_at",
                "cpu_percent", "memory_percent", "disk_percent",
                "memory_used", "memory_total", "disk_used", "disk_total", "net_rx", "net_tx")));
        assertEquals(12, payloadRequired.size());
    }

    /** 合法样例序列化后满足 schema 声明的字段类型与枚举（对照 payload 属性名）。 */
    @Test
    void validMessageShouldSerializeAgainstSchemaFields() throws Exception {
        JsonNode schema = schema();
        JsonNode payloadProperties = schema.path("properties").path("payload").path("properties");
        MetricsReportedMessage message = validMessage();
        JsonNode json = MAPPER.valueToTree(message);
        assertNotNull(json.path("event_id").asText());
        assertEquals("metrics.reported", json.path("event_type").asText());
        assertEquals(1, json.path("schema_version").asInt());
        assertEquals("metrics-service", json.path("producer").asText());
        JsonNode payload = json.path("payload");
        var fieldNames = payloadProperties.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            assertTrue(payload.has(field), "schema field " + field + " missing from serialized payload");
        }
        // 数值范围与校验器一致：百分比 0-100。
        assertEquals(100, payloadProperties.path("cpu_percent").path("maximum").asInt());
        assertEquals(0, payloadProperties.path("memory_used").path("minimum").asInt());
    }

    /** 缺失必填字段（如 message_id）不满足 schema required（对照校验器会拒绝）。 */
    @Test
    void messageMissingRequiredFieldShouldFailSchemaRequired() throws Exception {
        JsonNode schema = schema();
        List<String> payloadRequired = stringValues(schema.path("properties").path("payload").path("required"));

        Payload payload = new Payload(1L, null, "2026-08-05T00:00:00Z",
                new BigDecimal("35.5"), new BigDecimal("62.1"),
                4096L, 8192L, new BigDecimal("48.0"), 10L, 50L, 100L, 200L,
                null, new BigDecimal("0.5"));
        JsonNode json = MAPPER.valueToTree(new MetricsReportedMessage("22222222-2222-4222-8222-222222222222",
                "metrics.reported", 1, "2026-08-05T00:00:00Z", "metrics-service", payload));
        for (String field : payloadRequired) {
            if (field.equals("message_id")) {
                assertTrue(json.path("payload").path(field).isNull(),
                        "message_id should be null in the broken sample");
            }
        }
        // 与校验器行为对照：该样例必须被拒绝。
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MetricsReportedMessageValidator().validate(MAPPER.treeToValue(json, MetricsReportedMessage.class)));
    }
}

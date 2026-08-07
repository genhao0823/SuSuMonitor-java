package com.susumonitor.server.module.metrics.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susumonitor.server.module.metrics.entity.MetricsEntity;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 按冻结契约（message-contracts-v1.md §二/§三）构建 metrics.reported 事件信封。
 *
 * <p>信封字段固定 snake_case；occurred_at 使用注入时钟的 UTC 时刻；
 * temperature/load_avg 允许 null 并输出 JSON null。可选字段
 * trace_id/correlation_id 本阶段（MVP-10）不携带。</p>
 */
@Component
public class OutboxEnvelopeFactory {

    /** 契约事件类型（消费侧校验复用）。 */
    public static final String EVENT_TYPE = "metrics.reported";

    static final String PRODUCER = "metrics-service";

    /** 契约 schema 版本（消费侧校验复用）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 契约时间格式（UTC ISO-8601 固定带秒，与 message-contracts-v1 示例一致）。 */
    private static final DateTimeFormatter CONTRACT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;

    private final Clock clock;

    /** 注入 JSON 序列化器与应用时钟。 */
    public OutboxEnvelopeFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 构建冻结信封 JSON。
     *
     * @param entity    已落库的指标实体（collected_at 为 UTC 语义）
     * @param messageId Agent 上报消息 UUID（入口幂等键，非 event_id）
     * @param eventId   事件 UUID（消费侧幂等主键）
     * @return 信封 JSON 字符串
     */
    public String build(MetricsEntity entity, String messageId, String eventId) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("event_id", eventId);
        envelope.put("event_type", EVENT_TYPE);
        envelope.put("schema_version", SCHEMA_VERSION);
        envelope.put("occurred_at", OffsetDateTime.now(clock).format(CONTRACT_TIMESTAMP));
        envelope.put("producer", PRODUCER);
        envelope.set("payload", buildPayload(entity, messageId));
        return envelope.toString();
    }

    /**
     * 构建载荷 JSON 节点，映射指标实体的全部字段。
     *
     * @param entity 已落库的指标实体
     * @param messageId Agent 上报消息 UUID
     * @return 载荷 JSON 节点
     */
    private ObjectNode buildPayload(MetricsEntity entity, String messageId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("server_id", entity.getServerId());
        payload.put("message_id", messageId);
        payload.put("collected_at", entity.getCollectedAt().atOffset(ZoneOffset.UTC).format(CONTRACT_TIMESTAMP));
        payload.put("cpu_percent", entity.getCpuPercent());
        payload.put("memory_percent", entity.getMemoryPercent());
        payload.put("memory_used", entity.getMemoryUsed());
        payload.put("memory_total", entity.getMemoryTotal());
        payload.put("disk_percent", entity.getDiskPercent());
        payload.put("disk_used", entity.getDiskUsed());
        payload.put("disk_total", entity.getDiskTotal());
        payload.put("net_rx", entity.getNetRx());
        payload.put("net_tx", entity.getNetTx());
        putNullableDecimal(payload, "temperature", entity.getTemperature());
        putNullableDecimal(payload, "load_avg", entity.getLoadAvg());
        return payload;
    }

    /**
     * 向 JSON 节点写入可空十进制字段，null 时写入 JSON null。
     *
     * @param node 目标 JSON 节点
     * @param field 字段名
     * @param value 十进制数值，允许 null
     */
    private void putNullableDecimal(ObjectNode node, String field, java.math.BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}

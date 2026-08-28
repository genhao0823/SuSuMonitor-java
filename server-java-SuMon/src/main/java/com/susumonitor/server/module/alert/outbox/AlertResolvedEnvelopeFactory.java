package com.susumonitor.server.module.alert.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 按冻结契约（message-contracts-v1.md §二/§五）构建 alert.resolved 事件信封。
 *
 * <p>信封字段固定 snake_case；occurred_at 使用注入时钟的 UTC 时刻（秒级格式）。
 * payload 只携带契约冻结字段（server_id/rule_id/record_id/metric/level/status/
 * triggered_at/resolved_at），不携带触发值——恢复事件表达"已恢复"语义，
 * record 中的触发值不是恢复时刻的值，不得作为恢复载荷。</p>
 *
 * <p>与 {@code AlertTriggeredEnvelopeFactory} 对称；恢复事件不得复用
 * {@code alert.triggered.v1}（message-contracts-v1.md §四语义声明）。</p>
 */
@Component
public class AlertResolvedEnvelopeFactory {

    /** 契约事件类型（消费侧校验复用）。 */
    public static final String EVENT_TYPE = "alert.resolved";

    /** 契约路由键（版本化名，发布器按行路由复用）。 */
    public static final String ROUTING_KEY = "alert.resolved.v1";

    /** 契约生产模块标识（rabbitmq-topology-v1.md §二，消费侧校验复用）。 */
    public static final String PRODUCER = "alert-service";

    /** 契约 schema 版本（消费侧校验复用）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 契约时间格式（UTC ISO-8601 固定带秒，与 message-contracts-v1 示例一致）。 */
    private static final DateTimeFormatter CONTRACT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;

    private final Clock clock;

    /** 注入 JSON 序列化器与应用时钟。 */
    public AlertResolvedEnvelopeFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 构建冻结信封 JSON。
     *
     * @param record  已恢复的告警记录 VO（status=resolved，resolvedAt 已落库）
     * @param eventId 事件 UUID（消费侧幂等主键，与 outbox 行 event_id 一致）
     * @return 信封 JSON 字符串
     */
    public String build(AlertRecordVo record, String eventId) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("event_id", eventId);
        envelope.put("event_type", EVENT_TYPE);
        envelope.put("schema_version", SCHEMA_VERSION);
        envelope.put("occurred_at", OffsetDateTime.now(clock).format(CONTRACT_TIMESTAMP));
        envelope.put("producer", PRODUCER);
        envelope.set("payload", buildPayload(record));
        return envelope.toString();
    }

    /**
     * 构建契约 §五 载荷 JSON 节点，映射恢复记录 VO 的冻结字段。
     *
     * @param record 已恢复的告警记录 VO
     * @return 载荷 JSON 节点
     */
    private ObjectNode buildPayload(AlertRecordVo record) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("server_id", record.getServerId());
        payload.put("rule_id", record.getRuleId());
        payload.put("record_id", record.getId());
        payload.put("metric", record.getMetric());
        payload.put("level", record.getLevel());
        payload.put("status", record.getStatus());
        payload.put("triggered_at", record.getTriggeredAt().format(CONTRACT_TIMESTAMP));
        payload.put("resolved_at", record.getResolvedAt().format(CONTRACT_TIMESTAMP));
        return payload;
    }
}

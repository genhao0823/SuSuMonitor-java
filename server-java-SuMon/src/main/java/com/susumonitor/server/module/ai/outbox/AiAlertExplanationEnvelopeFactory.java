package com.susumonitor.server.module.ai.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susumonitor.server.module.alert.enums.AlertMetric;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 按冻结契约（message-contracts-v1.md §二/§六）构建 ai.alert.explanation.requested 事件信封。
 *
 * <p>payload 只携带契约冻结的 8 个白名单字段（server_id/rule_id/record_id/metric/
 * current_value/threshold_value/level/triggered_at），不携带告警展示文本与任何凭据；
 * 每条告警记录至多登记一条解释请求。事件登记必须在调用方消费事务内执行
 * （经 OutboxService），随事务回滚一并回滚。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public class AiAlertExplanationEnvelopeFactory {

    /** 契约事件类型（消费侧校验复用）。 */
    public static final String EVENT_TYPE = "ai.alert.explanation.requested";

    /** 契约路由键（版本化名，发布器按行路由复用）。 */
    public static final String ROUTING_KEY = "ai.alert.explanation.requested.v1";

    /** 契约生产模块标识（rabbitmq-topology-v1.md §二，消费侧校验复用）。 */
    public static final String PRODUCER = "ai-service";

    /** 契约 schema 版本（消费侧校验复用）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 契约时间格式（UTC ISO-8601 固定带秒，与 message-contracts-v1 示例一致）。 */
    private static final DateTimeFormatter CONTRACT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;

    private final Clock clock;

    /** 注入 JSON 序列化器与应用时钟。 */
    public AiAlertExplanationEnvelopeFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 构建冻结信封 JSON。
     *
     * @param record 已落库的告警记录 VO（触发值/阈值/级别为契约语义）
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
     * 构建契约 §六 载荷 JSON 节点，映射告警记录 VO 的冻结字段。
     *
     * @param record 已落库的告警记录 VO
     * @return 载荷 JSON 节点
     */
    private ObjectNode buildPayload(AlertRecordVo record) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("server_id", record.getServerId());
        payload.put("rule_id", record.getRuleId());
        payload.put("record_id", record.getId());
        payload.put("metric", AlertMetric.toEventMetric(record.getMetric()));
        payload.put("current_value", record.getCurrentValue());
        payload.put("threshold_value", record.getThresholdValue());
        payload.put("level", record.getLevel());
        payload.put("triggered_at", record.getTriggeredAt().format(CONTRACT_TIMESTAMP));
        return payload;
    }
}

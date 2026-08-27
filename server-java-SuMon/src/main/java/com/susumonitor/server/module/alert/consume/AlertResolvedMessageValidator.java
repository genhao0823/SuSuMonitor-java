package com.susumonitor.server.module.alert.consume;

import com.susumonitor.server.module.alert.outbox.AlertResolvedEnvelopeFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * alert.resolved.v1 字段级契约校验器。
 *
 * <p>只校验消息可在通知分发侧安全消费所需的不变量（信封、身份、契约枚举集、
 * 恢复语义状态）；失败由消费方分类为不可重试数据错误。不记录原始消息内容，
 * 避免日志泄漏未经审查的 payload。</p>
 */
@Component
public class AlertResolvedMessageValidator {

    /** 契约生产模块标识（与 AlertResolvedEnvelopeFactory 同源）。 */
    private static final String PRODUCER = AlertResolvedEnvelopeFactory.PRODUCER;

    /** 冻结指标名集合（message-contracts-v1.md §五 字段规则，与触发事件一致）。 */
    private static final Set<String> FROZEN_METRICS = Set.of(
            "cpu", "memory", "disk", "temperature", "load_avg");

    /** 恢复事件固定状态（不得复用触发语义伪装恢复）。 */
    private static final String RESOLVED_STATUS = "resolved";

    /** 校验冻结的 alert.resolved.v1 信封及载荷。 */
    public void validate(AlertResolvedMessage message) {
        if (message == null || !isUuid(message.eventId()) || !PRODUCER.equals(message.producer())
                || !isUtcOffsetDateTime(message.occurredAt()) || message.payload() == null) {
            throw new IllegalArgumentException("invalid alert event envelope");
        }

        AlertResolvedMessage.Payload payload = message.payload();
        if (payload.serverId() == null || payload.serverId() <= 0
                || payload.ruleId() == null || payload.ruleId() <= 0
                || payload.recordId() == null || payload.recordId() <= 0) {
            throw new IllegalArgumentException("invalid alert event identity");
        }
        if (payload.metric() == null || !FROZEN_METRICS.contains(payload.metric())
                || payload.level() == null || payload.level().isBlank()
                || !RESOLVED_STATUS.equals(payload.status())
                || !isUtcOffsetDateTime(payload.triggeredAt())
                || !isUtcOffsetDateTime(payload.resolvedAt())) {
            throw new IllegalArgumentException("invalid alert resolved event values");
        }
    }

    /** 校验字符串是否为合法 UUID。 */
    private boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 校验字符串是否为 UTC 偏移的日期时间格式。 */
    private boolean isUtcOffsetDateTime(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return OffsetDateTime.parse(value).getOffset().equals(ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

package com.susumonitor.server.module.alert.consume;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * metrics.reported.v1 字段级契约校验器。
 *
 * <p>只校验消息可在 Alert 侧安全消费所需的不变量；失败由调用方分类为不可重试数据错误。
 * 不记录原始消息内容，避免日志中出现未经审查的 payload。</p>
 */
@Component
public class MetricsReportedMessageValidator {

    private static final String PRODUCER = "metrics-service";

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /** 校验冻结的 metrics.reported.v1 信封及指标载荷。 */
    public void validate(MetricsReportedMessage message) {
        if (message == null || !isUuid(message.eventId()) || !PRODUCER.equals(message.producer())
                || !isUtcOffsetDateTime(message.occurredAt()) || message.payload() == null) {
            throw new IllegalArgumentException("invalid metrics event envelope");
        }

        MetricsReportedMessage.Payload payload = message.payload();
        if (payload.serverId() == null || payload.serverId() <= 0 || !isUuid(payload.messageId())
                || !isUtcOffsetDateTime(payload.collectedAt())) {
            throw new IllegalArgumentException("invalid metrics event identity or timestamp");
        }
        validatePercent(payload.cpuPercent());
        validatePercent(payload.memoryPercent());
        validatePercent(payload.diskPercent());
        validateRequiredNonNegative(payload.memoryUsed());
        validateRequiredNonNegative(payload.memoryTotal());
        validateRequiredNonNegative(payload.diskUsed());
        validateRequiredNonNegative(payload.diskTotal());
        validateRequiredNonNegative(payload.netRx());
        validateRequiredNonNegative(payload.netTx());
        if (payload.memoryUsed() > payload.memoryTotal() || payload.diskUsed() > payload.diskTotal()
                || isNegative(payload.temperature()) || isNegative(payload.loadAvg())) {
            throw new IllegalArgumentException("invalid metrics event values");
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

    /** 校验百分比值在 0-100 范围内。 */
    private void validatePercent(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException("invalid metrics percentage");
        }
    }

    /** 校验 Long 值非空且非负。 */
    private void validateRequiredNonNegative(Long value) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException("invalid metrics quantity");
        }
    }

    /** 判断 BigDecimal 是否为负数。 */
    private boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }
}

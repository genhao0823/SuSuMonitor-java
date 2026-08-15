package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * alert.resolved.v1 契约校验器：合法信封通过，身份/指标/状态/时间越界拒绝。
 */
class AlertResolvedMessageValidatorTests {

    private final AlertResolvedMessageValidator validator = new AlertResolvedMessageValidator();

    /** 合法恢复信封（§五 8 字段，UTC 时间）应通过。 */
    @Test
    void validEnvelopePasses() {
        assertDoesNotThrow(() -> validator.validate(message("cpu", "resolved",
                "2026-08-15T10:00:05Z", "2026-08-15T11:30:05Z")));
    }

    /** 事件 ID 非 UUID：拒绝。 */
    @Test
    void invalidEventIdRejects() {
        AlertResolvedMessage message = message("cpu", "resolved",
                "2026-08-15T10:00:05Z", "2026-08-15T11:30:05Z");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new AlertResolvedMessage("not-a-uuid", message.eventType(),
                        message.schemaVersion(), message.occurredAt(), message.producer(), message.payload())));
    }

    /** 生产模块标识不符：拒绝。 */
    @Test
    void wrongProducerRejects() {
        AlertResolvedMessage message = message("cpu", "resolved",
                "2026-08-15T10:00:05Z", "2026-08-15T11:30:05Z");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(new AlertResolvedMessage(message.eventId(), message.eventType(),
                        message.schemaVersion(), message.occurredAt(), "other-service", message.payload())));
    }

    /** 冻结指标集外的 metric：拒绝。 */
    @Test
    void unknownMetricRejects() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message("gpu", "resolved",
                "2026-08-15T10:00:05Z", "2026-08-15T11:30:05Z")));
    }

    /** 非 resolved 状态（复用触发语义伪装恢复）：拒绝。 */
    @Test
    void nonResolvedStatusRejects() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message("cpu", "unread",
                "2026-08-15T10:00:05Z", "2026-08-15T11:30:05Z")));
    }

    /** 时间非 UTC（本地偏移）：拒绝。 */
    @Test
    void nonUtcTimestampRejects() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message("cpu", "resolved",
                "2026-08-15T10:00:05+08:00", "2026-08-15T11:30:05Z")));
    }

    /** resolved_at 缺失：拒绝。 */
    @Test
    void missingResolvedAtRejects() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(message("cpu", "resolved",
                "2026-08-15T10:00:05Z", null)));
    }

    private AlertResolvedMessage message(String metric, String status, String triggeredAt, String resolvedAt) {
        return new AlertResolvedMessage(
                "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0",
                "alert.resolved",
                1,
                "2026-08-15T12:00:06Z",
                "alert-service",
                new AlertResolvedMessage.Payload(123L, 456L, 789L, metric, "warning", status,
                        triggeredAt, resolvedAt));
    }
}

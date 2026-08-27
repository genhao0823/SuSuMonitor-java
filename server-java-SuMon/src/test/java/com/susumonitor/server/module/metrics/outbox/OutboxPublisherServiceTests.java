package com.susumonitor.server.module.metrics.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 验证 Outbox 发布器的 Confirm 分支、退避重试、异常隔离与空批次行为。
 * 全部 mock，不依赖真实 RabbitMQ。
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-31T10:00:00Z"), ZoneOffset.UTC);
    private static final String EXCHANGE = "susumonitor.events";
    private static final String ROUTING_KEY = "metrics.reported.v1";

    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;

    private OutboxPublisherServiceImpl service;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getRabbitmq().setExchange(EXCHANGE);
        properties.getRabbitmq().setBatchSize(100);
        properties.getRabbitmq().setPublishTimeoutMs(1000);
        properties.getRabbitmq().setMaxBackoffSeconds(300);
        service = new OutboxPublisherServiceImpl(outboxMapper, rabbitTemplate, transactionTemplate, properties, CLOCK);
    }

    private void stubRows(OutboxEntity... rows) {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> List.of(rows));
    }

    private OutboxEntity row(Long id, int attempts) {
        OutboxEntity row = new OutboxEntity();
        row.setId(id);
        row.setEventId("event-" + id);
        row.setRoutingKey(ROUTING_KEY);
        row.setPayload("{}");
        row.setAttempts(attempts);
        row.setStatus(OutboxStatus.PENDING.ruleValue());
        return row;
    }

    private void confirmWith(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack reason"));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(EXCHANGE), anyString(), anyString(), any(CorrelationData.class));
    }

    /** Broker ack 时标记 published，参数为行 id 与当前时钟时刻。 */
    @Test
    void ackShouldMarkPublished() {
        stubRows(row(1L, 0));
        confirmWith(true);

        int published = service.publishOnce();

        assertEquals(1, published);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), eq("{}"), any(CorrelationData.class));
        verify(outboxMapper).markPublished(eq(1L), eq(LocalDateTime.of(2026, 7, 31, 10, 0, 0)));
        verify(outboxMapper, never()).markRetry(any(), anyInt(), any(), any());
    }

    /** Broker nack 时回写退避：attempts+1，next_attempt_at = now + 2^attempts 秒。 */
    @Test
    void nackShouldScheduleBackoffRetry() {
        stubRows(row(1L, 0));
        confirmWith(false);

        int published = service.publishOnce();

        assertEquals(0, published);
        verify(outboxMapper, never()).markPublished(any(), any());
        verify(outboxMapper).markRetry(eq(1L), eq(1),
                eq(LocalDateTime.of(2026, 7, 31, 10, 0, 2)), eq("broker nack"));
    }

    /** Confirm 超时（future 未完成）时同样回写退避，不抛异常。 */
    @Test
    void confirmTimeoutShouldScheduleBackoffRetry() {
        stubRows(row(1L, 2));
        // convertAndSend 为 void，mock 默认不完成 future，触发 publish-timeout-ms（1000ms）超时。

        int published = service.publishOnce();

        assertEquals(0, published);
        // attempts=2 -> 3，退避 2^3=8 秒。
        verify(outboxMapper).markRetry(eq(1L), eq(3),
                eq(LocalDateTime.of(2026, 7, 31, 10, 0, 8)), anyString());
    }

    /** 退避封顶：attempts 较大时 next_attempt_at 不超过 max-backoff-seconds。 */
    @Test
    void backoffShouldCapAtMaxSeconds() {
        stubRows(row(1L, 9));
        confirmWith(false);

        service.publishOnce();

        // 2^10=1024 秒，封顶 300 秒。
        verify(outboxMapper).markRetry(eq(1L), eq(10),
                eq(LocalDateTime.of(2026, 7, 31, 10, 5, 0)), anyString());
    }

    /** 单行发布抛异常时记录退避，且不中断同批其余行。 */
    @Test
    void singleRowFailureShouldNotBreakBatch() {
        stubRows(row(1L, 0), row(2L, 0));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            if ("1".equals(correlationData.getId())) {
                throw new AmqpException("connection refused");
            }
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(EXCHANGE), anyString(), anyString(), any(CorrelationData.class));

        int published = service.publishOnce();

        assertEquals(1, published);
        verify(outboxMapper).markRetry(eq(1L), eq(1), any(), eq("connection refused"));
        verify(outboxMapper).markPublished(eq(2L), any());
    }

    /** 同一批内不同行按各自 routing_key（V25）路由到对应队列。 */
    @Test
    void rowsShouldPublishWithPerRowRoutingKey() {
        OutboxEntity metricsRow = row(1L, 0);
        metricsRow.setRoutingKey("metrics.reported.v1");
        OutboxEntity alertRow = row(2L, 0);
        alertRow.setRoutingKey("alert.triggered.v1");
        stubRows(metricsRow, alertRow);
        confirmWith(true);

        int published = service.publishOnce();

        assertEquals(2, published);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("metrics.reported.v1"), eq("{}"), any(CorrelationData.class));
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("alert.triggered.v1"), eq("{}"), any(CorrelationData.class));
        verify(outboxMapper).markPublished(eq(1L), any());
        verify(outboxMapper).markPublished(eq(2L), any());
    }

    /** 无待发布行时零交互。 */
    @Test
    void emptyBatchShouldDoNothing() {
        when(transactionTemplate.execute(any())).thenReturn(List.of());

        int published = service.publishOnce();

        assertEquals(0, published);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
        verify(outboxMapper, never()).markPublished(any(), any());
        verify(outboxMapper, never()).markRetry(any(), anyInt(), any(), any());
    }
}

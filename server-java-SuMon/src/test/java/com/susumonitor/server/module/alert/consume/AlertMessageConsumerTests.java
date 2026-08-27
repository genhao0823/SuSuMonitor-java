package com.susumonitor.server.module.alert.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.alert.service.AlertEvaluationService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AlertMessageConsumer 单元测试（全 mock，不依赖 Broker/DB）。
 *
 * <p>AUTO 确认模式下 ACK 由容器在监听方法正常返回后执行，本测试只断言
 * 业务行为（评估调用 / 幂等记录插入 / 不可重试异常分类）。</p>
 */
class AlertMessageConsumerTests {

    private static final String EVENT_ID = "9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AlertEvaluationService evaluationService;

    private ConsumeRecordMapper consumeRecordMapper;

    private TransactionTemplate transactionTemplate;

    private AlertMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        evaluationService = mock(AlertEvaluationService.class);
        consumeRecordMapper = mock(ConsumeRecordMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // 模拟 TransactionTemplate：直接执行回调（真实事务由 Spring 管理，这里只验证业务调用链）。
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        consumer = new AlertMessageConsumer(objectMapper, evaluationService, consumeRecordMapper,
                transactionTemplate, Clock.fixed(Instant.parse("2026-07-31T08:00:00Z"), ZoneOffset.UTC),
                new MetricsReportedMessageValidator());
    }

    @Test
    void idempotentHitSkipsEvaluationAndInsert() {
        when(consumeRecordMapper.existsConsumed(AlertMessageConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(true);

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(evaluationService, never()).evaluate(any());
        verify(consumeRecordMapper, never()).upsertConsumed(any());
    }

    @Test
    void firstConsumeEvaluatesAndInsertsRecord() {
        when(consumeRecordMapper.existsConsumed(AlertMessageConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(evaluationService).evaluate(any());
        verify(consumeRecordMapper).upsertConsumed(org.mockito.ArgumentMatchers.argThat(record ->
                AlertMessageConsumer.CONSUMER_NAME.equals(record.getConsumer())
                        && EVENT_ID.equals(record.getEventId())
                        && ConsumeStatus.CONSUMED.ruleValue().equals(record.getStatus())
                        && record.getAttempts() == 0));
    }

    @Test
    void unparseableBodyRejectsWithoutRequeue() {
        Message message = new Message("not-a-json{{".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(evaluationService);
        verify(consumeRecordMapper, never()).upsertConsumed(any());
    }

    @Test
    void unsupportedSchemaVersionRejectsWithoutRequeue() {
        Message message = new Message(envelopeJson(EVENT_ID, 2).getBytes(StandardCharsets.UTF_8),
                new MessageProperties());

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(evaluationService);
        verify(consumeRecordMapper, never()).upsertConsumed(any());
    }

    @Test
    void invalidEventIdRejectsWithoutCallingBusinessDependencies() {
        Message message = new Message(envelopeJson("not-a-uuid", 1).getBytes(StandardCharsets.UTF_8),
                new MessageProperties());

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(consumeRecordMapper, evaluationService);
    }

    @Test
    void invalidPayloadValueRejectsWithoutCallingBusinessDependencies() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server_id", 1L);
        payload.put("message_id", "1a08f7b1-51c8-4b46-929a-8879f349a3a2");
        payload.put("collected_at", "2026-07-31T07:59:59Z");
        payload.put("cpu_percent", 101);
        payload.put("memory_percent", 70.1);
        payload.put("memory_used", 8192L);
        payload.put("memory_total", 16384L);
        payload.put("disk_percent", 55.2);
        payload.put("disk_used", 1024L);
        payload.put("disk_total", 2048L);
        payload.put("net_rx", 1000L);
        payload.put("net_tx", 2000L);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", EVENT_ID);
        envelope.put("event_type", "metrics.reported");
        envelope.put("schema_version", 1);
        envelope.put("occurred_at", "2026-07-31T08:00:00Z");
        envelope.put("producer", "metrics-service");
        envelope.put("payload", payload);

        assertThatThrownBy(() -> consumer.onMessage(jsonMessage(envelope)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(consumeRecordMapper, evaluationService);
    }
    @Test
    void evaluationExceptionPropagates() {
        when(consumeRecordMapper.existsConsumed(AlertMessageConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);
        doThrow(new RuntimeException("db unavailable")).when(evaluationService).evaluate(any());

        assertThatThrownBy(() -> consumer.onMessage(envelopeMessage(EVENT_ID, 1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db unavailable");
    }

    private Message jsonMessage(Map<String, Object> envelope) {
        try {
            return new Message(objectMapper.writeValueAsBytes(envelope), new MessageProperties());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Message envelopeMessage(String eventId, int schemaVersion) {
        return new Message(envelopeJson(eventId, schemaVersion).getBytes(StandardCharsets.UTF_8),
                new MessageProperties());
    }

    /** 构造符合冻结契约 §三 的信封 JSON（snake_case）。 */
    private String envelopeJson(String eventId, int schemaVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server_id", 1L);
        payload.put("message_id", "1a08f7b1-51c8-4b46-929a-8879f349a3a2");
        payload.put("collected_at", "2026-07-31T07:59:59Z");
        payload.put("cpu_percent", 80.5);
        payload.put("memory_percent", 70.1);
        payload.put("memory_used", 8192L);
        payload.put("memory_total", 16384L);
        payload.put("disk_percent", 55.2);
        payload.put("disk_used", 1024L);
        payload.put("disk_total", 2048L);
        payload.put("net_rx", 1000L);
        payload.put("net_tx", 2000L);
        payload.put("temperature", 65.3);
        payload.put("load_avg", 2.5);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", "metrics.reported");
        envelope.put("schema_version", schemaVersion);
        envelope.put("occurred_at", "2026-07-31T08:00:00Z");
        envelope.put("producer", "metrics-service");
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

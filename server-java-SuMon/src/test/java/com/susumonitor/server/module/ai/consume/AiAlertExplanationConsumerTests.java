package com.susumonitor.server.module.ai.consume;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.ai.model.AiAlertFacts;
import com.susumonitor.server.module.ai.notify.AiAlertExplanationNotifier;
import com.susumonitor.server.module.ai.service.AiAlertExplanationService;
import com.susumonitor.server.module.ai.vo.AiAlertExplanationVo;
import com.susumonitor.server.module.alert.consume.ConsumeRecordEntity;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.consume.ConsumeStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AiAlertExplanationConsumer 单元测试（全 mock，不依赖 Broker/DB）。
 *
 * <p>断言幂等命中、事务内外边界（provider 调用在外、落库+幂等记录在内）、
 * 不可重试异常分类与服务器缺失跳过语义。</p>
 */
class AiAlertExplanationConsumerTests {

    private static final String EVENT_ID = "d1a7c3e0-4f8b-4c2a-9b6d-2e5f8a1c7b3d";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiAlertExplanationService explanationService;

    private AiAlertExplanationNotifier explanationNotifier;

    private ConsumeRecordMapper consumeRecordMapper;

    private TransactionTemplate transactionTemplate;

    private AiAlertExplanationConsumer consumer;

    @BeforeEach
    void setUp() {
        explanationService = mock(AiAlertExplanationService.class);
        explanationNotifier = mock(AiAlertExplanationNotifier.class);
        consumeRecordMapper = mock(ConsumeRecordMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // 模拟 TransactionTemplate：execute 与 executeWithoutResult 都直接执行回调。
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        // Spring 6 中 executeWithoutResult 以 Consumer<TransactionStatus> 表达。
        doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any(Consumer.class));
        consumer = new AiAlertExplanationConsumer(objectMapper, explanationService, explanationNotifier,
                consumeRecordMapper, transactionTemplate,
                Clock.fixed(Instant.parse("2026-09-04T12:00:10Z"), ZoneOffset.UTC),
                new AiAlertExplanationMessageValidator(), Runnable::run);
    }

    /** 幂等命中：不解释、不落库、不推送。 */
    @Test
    void idempotentHitSkipsExplainAndSave() {
        when(consumeRecordMapper.existsConsumed(AiAlertExplanationConsumer.CONSUMER_NAME, EVENT_ID))
                .thenReturn(true);

        consumer.onMessage(envelopeMessage());

        verify(explanationService, never()).explain(any());
        verify(explanationService, never()).save(any(), any(), any(), anyLong());
        verifyNoInteractions(explanationNotifier);
    }

    /** 首次消费：事务外 explain → 事务内 save + 幂等记录 → 提交后补充通知。 */
    @Test
    void firstConsumeExplainsSavesAndNotifies() {
        when(consumeRecordMapper.existsConsumed(AiAlertExplanationConsumer.CONSUMER_NAME, EVENT_ID))
                .thenReturn(false);
        AiAlertExplanationVo explanation = new AiAlertExplanationVo();
        explanation.setSummary("CPU elevated.");
        when(explanationService.explain(any(AiAlertFacts.class))).thenReturn(explanation);

        consumer.onMessage(envelopeMessage());

        verify(explanationService).explain(argThat(facts ->
                facts.recordId() == 789L && facts.serverId() == 123L
                        && "cpu".equals(facts.metric()) && "warning".equals(facts.level())));
        verify(explanationService).save(eq(EVENT_ID), any(AiAlertFacts.class), eq(explanation), anyLong());
        verify(consumeRecordMapper).upsertConsumed(argThat(record ->
                AiAlertExplanationConsumer.CONSUMER_NAME.equals(record.getConsumer())
                        && EVENT_ID.equals(record.getEventId())
                        && ConsumeStatus.CONSUMED.ruleValue().equals(record.getStatus())));
        verify(explanationNotifier).dispatchSupplement(any(AiAlertFacts.class), eq(explanation));
    }

    /** 无法解析的信封：不可重试拒绝（进 DLQ），不触发任何业务依赖。 */
    @Test
    void unparseableBodyRejectsWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onMessage(rawMessage("{not-json")));

        verifyNoInteractions(explanationService, explanationNotifier, consumeRecordMapper);
    }

    /** schema_version 不支持：不可重试拒绝。 */
    @Test
    void unsupportedSchemaVersionRejects() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onMessage(envelopeMessage(2)));

        verifyNoInteractions(explanationService);
    }

    /** 字段契约校验失败：不可重试拒绝。 */
    @Test
    void invalidContractValueRejectsWithoutCallingBusinessDependencies() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onMessage(rawMessage(envelopeJson("not-a-uuid", 1))));

        verifyNoInteractions(explanationService, explanationNotifier);
    }

    /** 服务器已删除（40401）：跳过解释但仍落幂等记录，不推送不抛出。 */
    @Test
    void missingServerSkipsExplanationButConsumes() {
        when(consumeRecordMapper.existsConsumed(AiAlertExplanationConsumer.CONSUMER_NAME, EVENT_ID))
                .thenReturn(false);
        when(explanationService.explain(any(AiAlertFacts.class)))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        consumer.onMessage(envelopeMessage());

        verify(explanationService, never()).save(any(), any(), any(), anyLong());
        verify(consumeRecordMapper).upsertConsumed(any(ConsumeRecordEntity.class));
        verifyNoInteractions(explanationNotifier);
    }

    /** 其余业务异常（如 provider 不可用）向上传播：交由容器级有限重试 → DLQ。 */
    @Test
    void businessExceptionPropagatesForRetry() {
        when(consumeRecordMapper.existsConsumed(AiAlertExplanationConsumer.CONSUMER_NAME, EVENT_ID))
                .thenReturn(false);
        when(explanationService.explain(any(AiAlertFacts.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));

        assertThrows(BusinessException.class, () -> consumer.onMessage(envelopeMessage()));

        verify(explanationService, never()).save(any(), any(), any(), anyLong());
        verify(consumeRecordMapper, never()).upsertConsumed(any());
        verifyNoInteractions(explanationNotifier);
    }

    /** 事务内 save 抛出：异常传播使事务回滚语义成立（幂等记录不落库、不推送）。 */
    @Test
    void saveFailurePropagatesWithoutConsuming() {
        when(consumeRecordMapper.existsConsumed(AiAlertExplanationConsumer.CONSUMER_NAME, EVENT_ID))
                .thenReturn(false);
        when(explanationService.explain(any(AiAlertFacts.class))).thenReturn(new AiAlertExplanationVo());
        doThrow(new BusinessException(ErrorCode.DATABASE_ERROR))
                .when(explanationService).save(any(), any(), any(), anyLong());

        assertThrows(BusinessException.class, () -> consumer.onMessage(envelopeMessage()));

        verify(consumeRecordMapper, never()).upsertConsumed(any());
        verifyNoInteractions(explanationNotifier);
    }

    private Message envelopeMessage() {
        return envelopeMessage(1);
    }

    private Message envelopeMessage(int schemaVersion) {
        return rawMessage(envelopeJson(EVENT_ID, schemaVersion));
    }

    private String envelopeJson(String eventId, int schemaVersion) {
        return "{\"event_id\":\"" + eventId + "\",\"event_type\":\"ai.alert.explanation.requested\","
                + "\"schema_version\":" + schemaVersion + ",\"occurred_at\":\"2026-09-04T12:00:06Z\","
                + "\"producer\":\"ai-service\",\"payload\":{\"server_id\":123,\"rule_id\":456,"
                + "\"record_id\":789,\"metric\":\"cpu\",\"current_value\":92.5,\"threshold_value\":80.0,"
                + "\"level\":\"warning\",\"triggered_at\":\"2026-09-04T11:55:00Z\"}}";
    }

    private Message rawMessage(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}

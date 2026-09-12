package com.susumonitor.server.module.alert.consume;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.notification.AlertNotificationService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AlertTriggeredConsumer 单元测试（全 mock，不依赖 Broker/DB）。
 *
 * <p>AUTO 确认模式下 ACK 由容器在监听方法正常返回后执行，本测试只断言
 * 业务行为（通知排程 / 幂等记录插入 / 异步发送 / 不可重试异常分类）。</p>
 */
class AlertTriggeredConsumerTests {

    private static final String EVENT_ID = "ce4cb5a4-ff5c-4514-a7b9-1ab5cc7e81b0";
    private static final long SERVER_ID = 123L;
    private static final long RULE_ID = 456L;
    private static final long RECORD_ID = 789L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AlertNotificationService notificationService;

    private AlertRuleMapper ruleMapper;

    private AlertRecordMapper recordMapper;

    private ConsumeRecordMapper consumeRecordMapper;

    private TransactionTemplate transactionTemplate;

    private AlertTriggeredConsumer consumer;

    @BeforeEach
    void setUp() {
        notificationService = mock(AlertNotificationService.class);
        ruleMapper = mock(AlertRuleMapper.class);
        recordMapper = mock(AlertRecordMapper.class);
        consumeRecordMapper = mock(ConsumeRecordMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // 模拟 TransactionTemplate：直接执行回调（真实事务由 Spring 管理，这里只验证业务调用链）。
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        // F1 挂钩依赖：真实 AppProperties 默认 explanation.enabled=false，
        // 本测试类聚焦通知排程语义，不触发解释请求登记。
        com.susumonitor.server.config.AppProperties appProperties = new com.susumonitor.server.config.AppProperties();
        com.susumonitor.server.module.metrics.outbox.OutboxService outboxService =
                mock(com.susumonitor.server.module.metrics.outbox.OutboxService.class);
        org.springframework.beans.factory.ObjectProvider<com.susumonitor.server.module.ai.outbox.AiAlertExplanationEnvelopeFactory>
                explanationEnvelopeFactory = mock(org.springframework.beans.factory.ObjectProvider.class);
        consumer = new AlertTriggeredConsumer(objectMapper, notificationService, ruleMapper, recordMapper,
                consumeRecordMapper, transactionTemplate,
                Clock.fixed(Instant.parse("2026-07-28T12:00:06Z"), ZoneOffset.UTC),
                new AlertTriggeredMessageValidator(), appProperties, outboxService, explanationEnvelopeFactory);
    }

    /** 幂等命中：不排程、不插记录、不发送。 */
    @Test
    void idempotentHitSkipsDispatchAndInsert() {
        stubRule(activeRuleWithChannel());
        when(consumeRecordMapper.existsConsumed(AlertTriggeredConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(true);

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(notificationService, never()).scheduleNotifications(any(), any());
        verify(consumeRecordMapper, never()).upsertConsumed(any());
        verify(notificationService, never()).sendScheduled(any(), any(), any(), any());
    }

    /** 首次消费（规则有效 + 有渠道 + 记录存在）：排程通知 + 幂等记录 + 提交后异步发送。 */
    @Test
    void firstConsumeSchedulesAndSends() {
        AlertRuleEntity rule = activeRuleWithChannel();
        AlertRecordEntity record = record();
        stubRule(rule);
        when(recordMapper.selectRecordById(RECORD_ID)).thenReturn(record);
        when(consumeRecordMapper.existsConsumed(AlertTriggeredConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);
        when(notificationService.scheduleNotifications(eq(rule), any())).thenReturn(
                List.of(notificationEntity(RECORD_ID, "dingtalk")));

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(notificationService).scheduleNotifications(eq(rule), any());
        verify(consumeRecordMapper).upsertConsumed(argThat(r ->
                AlertTriggeredConsumer.CONSUMER_NAME.equals(r.getConsumer())
                        && EVENT_ID.equals(r.getEventId())
                        && ConsumeStatus.CONSUMED.ruleValue().equals(r.getStatus())
                        && r.getAttempts() == 0));
        verify(notificationService).sendScheduled(eq(RECORD_ID), any(), eq(rule), any());
    }

    /** 规则被禁用：仅幂等记录，不排程不发送。 */
    @Test
    void disabledRuleSkipsDispatchButConsumes() {
        AlertRuleEntity rule = activeRuleWithChannel();
        rule.setEnabled(false);
        stubRule(rule);
        when(consumeRecordMapper.existsConsumed(AlertTriggeredConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(notificationService, never()).scheduleNotifications(any(), any());
        verify(notificationService, never()).sendScheduled(any(), any(), any(), any());
        verify(consumeRecordMapper).upsertConsumed(any());
    }

    /** 规则无通知渠道：仅幂等记录，不排程不发送。 */
    @Test
    void ruleWithoutChannelSkipsDispatchButConsumes() {
        AlertRuleEntity rule = activeRuleWithChannel();
        rule.setNotifyEmail(null);
        rule.setNotifyDingtalk(null);
        rule.setNotifyWebhook(null);
        stubRule(rule);
        when(consumeRecordMapper.existsConsumed(AlertTriggeredConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(notificationService, never()).scheduleNotifications(any(), any());
        verify(notificationService, never()).sendScheduled(any(), any(), any(), any());
        verify(consumeRecordMapper).upsertConsumed(any());
    }

    /** 记录已不存在：仅幂等记录，不排程不发送（重试无意义）。 */
    @Test
    void missingRecordSkipsDispatchButConsumes() {
        stubRule(activeRuleWithChannel());
        when(recordMapper.selectRecordById(RECORD_ID)).thenReturn(null);
        when(consumeRecordMapper.existsConsumed(AlertTriggeredConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(notificationService, never()).scheduleNotifications(any(), any());
        verify(notificationService, never()).sendScheduled(any(), any(), any(), any());
        verify(consumeRecordMapper).upsertConsumed(any());
    }

    /** 记录已被恢复消费置 resolved（队列乱序）：迟到的触发消息不再排程通知，仅幂等记录。 */
    @Test
    void resolvedRecordSkipsDispatchButConsumes() {
        stubRule(activeRuleWithChannel());
        AlertRecordEntity resolved = record();
        resolved.setStatus("resolved");
        when(recordMapper.selectRecordById(RECORD_ID)).thenReturn(resolved);
        when(consumeRecordMapper.existsConsumed(AlertTriggeredConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);

        consumer.onMessage(envelopeMessage(EVENT_ID, 1));

        verify(notificationService, never()).scheduleNotifications(any(), any());
        verify(notificationService, never()).sendScheduled(any(), any(), any(), any());
        verify(consumeRecordMapper).upsertConsumed(any());
    }

    /** 非法 JSON：不可重试直接拒绝，无任何业务交互。 */
    @Test
    void unparseableBodyRejectsWithoutRequeue() {
        Message message = new Message("not-a-json{{".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(notificationService, consumeRecordMapper);
    }

    /** 不支持的 schema 版本：不可重试直接拒绝。 */
    @Test
    void unsupportedSchemaVersionRejectsWithoutRequeue() {
        Message message = new Message(envelopeJson(EVENT_ID, 2).getBytes(StandardCharsets.UTF_8),
                new MessageProperties());

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(notificationService, consumeRecordMapper);
    }

    /** 字段契约越界（非法 metric）：不可重试直接拒绝。 */
    @Test
    void invalidContractValueRejectsWithoutCallingBusinessDependencies() {
        Map<String, Object> envelope = envelopeMap(EVENT_ID, 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        payload.put("metric", "gpu");
        Message message = jsonMessage(envelope);

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(notificationService, consumeRecordMapper);
    }

    /** 业务异常原样传播（由容器级有限重试处理）。 */
    @Test
    void businessExceptionPropagates() {
        AlertRuleEntity rule = activeRuleWithChannel();
        stubRule(rule);
        when(recordMapper.selectRecordById(RECORD_ID)).thenReturn(record());
        when(consumeRecordMapper.existsConsumed(AlertTriggeredConsumer.CONSUMER_NAME, EVENT_ID)).thenReturn(false);
        doThrow(new RuntimeException("db unavailable")).when(notificationService)
                .scheduleNotifications(any(), any());

        assertThatThrownBy(() -> consumer.onMessage(envelopeMessage(EVENT_ID, 1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db unavailable");
    }

    // --- 辅助方法 ---

    private void stubRule(AlertRuleEntity rule) {
        when(ruleMapper.selectActiveRuleById(RULE_ID)).thenReturn(rule);
    }

    private AlertRuleEntity activeRuleWithChannel() {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(RULE_ID);
        rule.setEnabled(true);
        rule.setNotifyDingtalk("https://oapi.dingtalk.com/robot/send?access_token=test");
        return rule;
    }

    private AlertRecordEntity record() {
        AlertRecordEntity record = new AlertRecordEntity();
        record.setId(RECORD_ID);
        record.setRuleId(RULE_ID);
        record.setServerId(SERVER_ID);
        record.setMetric("cpu");
        record.setTriggeredAt(LocalDateTime.of(2026, 7, 28, 12, 0, 5));
        return record;
    }

    private AlertNotificationEntity notificationEntity(Long recordId, String channel) {
        AlertNotificationEntity entity = new AlertNotificationEntity();
        entity.setId(1L);
        entity.setAlertRecordId(recordId);
        entity.setChannel(channel);
        return entity;
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

    /** 构造符合冻结契约 §四 的信封 JSON（snake_case）。 */
    private String envelopeJson(String eventId, int schemaVersion) {
        try {
            return objectMapper.writeValueAsString(envelopeMap(eventId, schemaVersion));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> envelopeMap(String eventId, int schemaVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server_id", SERVER_ID);
        payload.put("rule_id", RULE_ID);
        payload.put("record_id", RECORD_ID);
        payload.put("metric", "cpu");
        payload.put("current_value", 92.5);
        payload.put("threshold_value", 80.0);
        payload.put("level", "warning");
        payload.put("status", "unread");
        payload.put("triggered_at", "2026-07-28T12:00:05Z");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", "alert.triggered");
        envelope.put("schema_version", schemaVersion);
        envelope.put("occurred_at", "2026-07-28T12:00:06Z");
        envelope.put("producer", "alert-service");
        envelope.put("payload", payload);
        return envelope;
    }
}
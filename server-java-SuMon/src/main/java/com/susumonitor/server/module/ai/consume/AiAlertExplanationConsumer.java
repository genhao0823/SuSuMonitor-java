package com.susumonitor.server.module.ai.consume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.ai.model.AiAlertFacts;
import com.susumonitor.server.module.ai.outbox.AiAlertExplanationEnvelopeFactory;
import com.susumonitor.server.module.ai.service.AiAlertExplanationService;
import com.susumonitor.server.module.ai.vo.AiAlertExplanationVo;
import com.susumonitor.server.module.alert.consume.ConsumeRecordEntity;
import com.susumonitor.server.module.alert.consume.ConsumeRecordMapper;
import com.susumonitor.server.module.alert.consume.ConsumeStatus;
import com.susumonitor.server.module.alert.consume.FailedConsumeRecordRecoverer;
import com.susumonitor.server.module.ai.notify.AiAlertExplanationNotifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 告警解释请求消费者（F1 告警智能解释）：幂等消费 ai.alert.explanation.requested.v1。
 *
 * <p>消费链路（2026-09-04 起）：alert-notifier 消费事务内登记解释请求事件（Outbox）→
 * 发布器路由到 {@code susumonitor.ai.alert.explanation} → 本消费者在事务外调用
 * provider 生成解释（LLM 调用耗时不入库事务），随后在同一数据库事务内落解释行
 * （ai_alert_explanations，V30）+ 消费幂等记录；事务提交后尽力而为推送补充通知
 * （{@link AiAlertExplanationNotifier}，失败只记日志，解释已可经 API 回看）。</p>
 *
 * <p>ACK 语义与 {@code AlertTriggeredConsumer} 一致：AUTO 确认；解析/字段契约不符抛
 * {@link AmqpRejectAndDontRequeueException} 直接进 DLQ；业务异常由容器级有限重试后
 * 经 {@link FailedConsumeRecordRecoverer} 失败留痕进 DLQ。解释失败绝不阻塞告警主链路
 * ——原告警通知在 alert-notifier 事务提交后已先行发出。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"susumonitor.rabbitmq.enabled", "susumonitor.ai.explanation.enabled"},
        havingValue = "true")
public class AiAlertExplanationConsumer {

    /** 消费者名：失败留痕 recoverer 与消费幂等记录共用（ConsumerQueueNames 同源）。 */
    public static final String CONSUMER_NAME = "ai-explainer";

    /** 消费的业务队列（rabbitmq-topology-v1.md §二 命名冻结）。 */
    public static final String QUEUE = "susumonitor.ai.alert.explanation";

    private final ObjectMapper objectMapper;

    private final AiAlertExplanationService explanationService;

    private final AiAlertExplanationNotifier explanationNotifier;

    private final ConsumeRecordMapper consumeRecordMapper;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    private final AiAlertExplanationMessageValidator messageValidator;

    private final org.springframework.core.task.AsyncTaskExecutor notificationExecutor;

    /** 注入反序列化器、解释服务、补充通知器、消费幂等访问、事务模板与通知执行器。 */
    public AiAlertExplanationConsumer(ObjectMapper objectMapper, AiAlertExplanationService explanationService,
            AiAlertExplanationNotifier explanationNotifier, ConsumeRecordMapper consumeRecordMapper,
            TransactionTemplate transactionTemplate, Clock clock,
            AiAlertExplanationMessageValidator messageValidator,
            @org.springframework.beans.factory.annotation.Qualifier("notificationExecutor")
            org.springframework.core.task.AsyncTaskExecutor notificationExecutor) {
        this.objectMapper = objectMapper;
        this.explanationService = explanationService;
        this.explanationNotifier = explanationNotifier;
        this.consumeRecordMapper = consumeRecordMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.messageValidator = messageValidator;
        this.notificationExecutor = notificationExecutor;
    }

    /**
     * 消费一条 ai.alert.explanation.requested.v1 消息。
     *
     * @param message 原始消息（UTF-8 JSON 信封）
     */
    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message) {
        AiAlertExplanationMessage envelope = parseEnvelope(message);
        if (consumeRecordMapper.existsConsumed(CONSUMER_NAME, envelope.eventId())) {
            // 幂等命中：已成功消费过（如 ACK 丢失后的重新投递），不重复生成解释。
            log.debug("consume idempotent hit, eventId={}", envelope.eventId());
            return;
        }
        AiAlertFacts facts = toFacts(envelope.payload());
        // provider 调用在事务外执行：LLM 耗时（秒级）不得拉长数据库事务。
        long started = System.nanoTime();
        AiAlertExplanationVo explanation;
        try {
            explanation = explanationService.explain(facts);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                // 目标服务器已删除或不存在：解释无意义，跳过并落幂等记录（重试无意义）。
                log.info("explanation skipped: server missing, recordId={}, eventId={}",
                        facts.recordId(), envelope.eventId());
                transactionTemplate.executeWithoutResult(status -> insertConsumeRecord(envelope));
                return;
            }
            // 其余业务异常（预算耗尽/provider 不可用/响应不合规）走容器有限重试 → DLQ。
            throw exception;
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        // 业务事务：解释落库与消费幂等记录同事务提交；返回后容器 ACK。
        transactionTemplate.executeWithoutResult(status -> {
            explanationService.save(envelope.eventId(), facts, explanation, durationMs);
            insertConsumeRecord(envelope);
        });
        // 事务已提交：解释已可回看，补充通知为尽力而为——在专用有界线程池异步发送，
        // 不阻塞消费线程；饱和/失败只记日志（与命令事后通知同一执行器与语义）。
        try {
            notificationExecutor.execute(() -> {
                try {
                    explanationNotifier.dispatchSupplement(facts, explanation);
                } catch (RuntimeException exception) {
                    log.warn("AI explanation supplement notification failed unexpectedly, recordId={}",
                            facts.recordId(), exception);
                }
            });
        } catch (org.springframework.core.task.TaskRejectedException exception) {
            log.warn("AI explanation supplement notification discarded (executor saturated), recordId={}",
                    facts.recordId());
        }
    }

    /** 将契约载荷转换为解释事实（字段语义一一对应，值不改动）。 */
    private AiAlertFacts toFacts(AiAlertExplanationMessage.Payload payload) {
        return new AiAlertFacts(payload.recordId(), payload.ruleId(), payload.serverId(),
                payload.metric(), payload.currentValue(), payload.thresholdValue(),
                payload.level(), payload.triggeredAt());
    }

    /**
     * 反序列化并校验信封；不可重试数据错误直接拒绝（进 DLQ）。
     */
    private AiAlertExplanationMessage parseEnvelope(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        AiAlertExplanationMessage envelope;
        try {
            envelope = objectMapper.readValue(body, AiAlertExplanationMessage.class);
        } catch (JsonProcessingException exception) {
            log.warn("consume rejected: unparseable explanation request envelope");
            throw new AmqpRejectAndDontRequeueException("unparseable explanation request envelope", exception);
        }
        if (envelope.schemaVersion() != AiAlertExplanationEnvelopeFactory.SCHEMA_VERSION
                || !AiAlertExplanationEnvelopeFactory.EVENT_TYPE.equals(envelope.eventType())) {
            log.warn("consume rejected: unsupported schemaVersion={} or eventType={}",
                    envelope.schemaVersion(), envelope.eventType());
            throw new AmqpRejectAndDontRequeueException("unsupported explanation request schema version or event type");
        }
        try {
            messageValidator.validate(envelope);
        } catch (IllegalArgumentException exception) {
            log.warn("consume rejected: invalid explanation request contract");
            throw new AmqpRejectAndDontRequeueException("invalid explanation request contract", exception);
        }
        return envelope;
    }

    /** 插入消费幂等记录，已存在则翻转为 consumed。 */
    private void insertConsumeRecord(AiAlertExplanationMessage envelope) {
        ConsumeRecordEntity record = new ConsumeRecordEntity();
        record.setConsumer(CONSUMER_NAME);
        record.setEventId(envelope.eventId());
        record.setStatus(ConsumeStatus.CONSUMED.ruleValue());
        record.setAttempts(0);
        record.setConsumedAt(LocalDateTime.now(clock));
        // upsert：若该事件此前失败留痕过（failed 行），重放成功后翻转回 consumed。
        consumeRecordMapper.upsertConsumed(record);
    }
}

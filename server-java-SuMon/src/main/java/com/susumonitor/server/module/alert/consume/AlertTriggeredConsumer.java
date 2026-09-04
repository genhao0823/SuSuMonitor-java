package com.susumonitor.server.module.alert.consume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.notification.AlertNotificationService;
import com.susumonitor.server.module.alert.outbox.AlertTriggeredEnvelopeFactory;
import com.susumonitor.server.module.ai.outbox.AiAlertExplanationEnvelopeFactory;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import com.susumonitor.server.module.metrics.outbox.OutboxService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 告警出站事件消费者（消息驱动外部通知）：幂等消费 alert.triggered.v1。
 *
 * <p>通知触发链路（2026-08-12 起）：评估事务登记 Outbox →
 * 发布器路由到 {@code susumonitor.alert.triggered} → 本消费者在消费事务内
 * 为规则配置的渠道排程通知（同事务插 pending 行 + 消费幂等记录），
 * 事务提交后异步发送（{@link AlertNotificationService#sendScheduled}）。
 * 旧版 AFTER_COMMIT 直呼通知的 {@code AlertNotificationPublisher} 已删除，
 * 保证同一 record 只触发一次通知。</p>
 *
 * <p>F1 告警智能解释挂钩（2026-09-04 起）：消费事务内同事务登记一条
 * {@code ai.alert.explanation.requested.v1} 解释请求事件（经 Outbox，
 * {@link AiAlertExplanationEnvelopeFactory} 构建白名单载荷），由 ai-explainer
 * 消费者异步生成解释。登记受 {@code susumonitor.ai.explanation.enabled} 独立
 * 开关门控（关闭时零事件）；登记失败随消费事务回滚，不影响通知排程语义。</p>
 *
 * <p>ACK 语义与错误分类与 {@link AlertMessageConsumer} 一致：AUTO 确认（事务提交
 * 后返回即 ACK）；解析/字段契约不符抛 {@link AmqpRejectAndDontRequeueException}
 * 直接进 DLQ；业务异常由容器级有限重试后进 DLQ。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class AlertTriggeredConsumer {

    static final String CONSUMER_NAME = "alert-notifier";

    static final String QUEUE = "susumonitor.alert.triggered";

    private final ObjectMapper objectMapper;

    private final AlertNotificationService notificationService;

    private final AlertRuleMapper ruleMapper;

    private final AlertRecordMapper recordMapper;

    private final ConsumeRecordMapper consumeRecordMapper;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    private final AlertTriggeredMessageValidator messageValidator;

    private final AppProperties appProperties;

    private final OutboxService outboxService;

    /** 解释请求信封工厂随 explanation.enabled 条件装配，缺席时挂钩静默降级。 */
    private final ObjectProvider<AiAlertExplanationEnvelopeFactory> explanationEnvelopeFactory;

    /** 注入反序列化器、通知服务、规则/记录数据访问与事务模板。 */
    public AlertTriggeredConsumer(ObjectMapper objectMapper, AlertNotificationService notificationService,
            AlertRuleMapper ruleMapper, AlertRecordMapper recordMapper, ConsumeRecordMapper consumeRecordMapper,
            TransactionTemplate transactionTemplate, Clock clock, AlertTriggeredMessageValidator messageValidator,
            AppProperties appProperties, OutboxService outboxService,
            ObjectProvider<AiAlertExplanationEnvelopeFactory> explanationEnvelopeFactory) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.consumeRecordMapper = consumeRecordMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.messageValidator = messageValidator;
        this.appProperties = appProperties;
        this.outboxService = outboxService;
        this.explanationEnvelopeFactory = explanationEnvelopeFactory;
    }

    /**
     * 消费一条 alert.triggered.v1 消息。
     *
     * @param message 原始消息（UTF-8 JSON 信封）
     */
    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message) {
        AlertTriggeredMessage envelope = parseEnvelope(message);
        if (consumeRecordMapper.existsConsumed(CONSUMER_NAME, envelope.eventId())) {
            // 幂等命中：已成功消费过（如 ACK 丢失后的重新投递），不重复排程通知。
            log.debug("consume idempotent hit, eventId={}", envelope.eventId());
            return;
        }
        // 业务事务：通知排程、解释请求登记与消费幂等记录同事务提交；返回后容器 ACK。
        DispatchResult scheduled = transactionTemplate.execute(status -> {
            DispatchResult result = dispatchNotifications(envelope);
            if (result != null) {
                enqueueExplanationRequest(result.record());
            }
            insertConsumeRecord(envelope);
            return result;
        });
        // 事务已提交：排程出的 pending 行已落库，异步发送（自调用线程不持有事务）。
        if (scheduled != null && !scheduled.notifications().isEmpty()) {
            notificationService.sendScheduled(scheduled.recordId(), scheduled.notifications(),
                    scheduled.rule(), scheduled.record());
        }
    }

    /**
     * 在消费事务内排程通知：规则有效且对应记录存在时加载上下文；规则配置了
     * 至少一个渠道才插 pending 行，否则仅返回上下文（供解释请求登记使用）。
     *
     * @return 排程结果（规则/记录/待发通知行），供事务提交后异步发送使用
     */
    private DispatchResult dispatchNotifications(AlertTriggeredMessage envelope) {
        AlertTriggeredMessage.Payload payload = envelope.payload();
        AlertRuleEntity rule = ruleMapper.selectActiveRuleById(payload.ruleId());
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            log.debug("alert notification skipped: rule unavailable, ruleId={}, recordId={}",
                    payload.ruleId(), payload.recordId());
            return null;
        }
        AlertRecordEntity record = recordMapper.selectRecordById(payload.recordId());
        if (record == null) {
            log.warn("alert notification skipped: record missing, recordId={}", payload.recordId());
            return null;
        }
        AlertRecordVo recordVo = toVo(record);
        List<AlertNotificationEntity> notifications = hasAnyChannel(rule)
                ? notificationService.scheduleNotifications(rule, recordVo)
                : List.of();
        return new DispatchResult(payload.recordId(), rule, recordVo, notifications);
    }

    /**
     * 在消费事务内登记 AI 解释请求事件（F1）：开关关闭或信封工厂缺席（同为开关
     * 装配条件）时不做任何事；登记失败抛出使消费事务回滚，与通知排程同生共死。
     *
     * @param record 已加载的告警记录 VO（载荷数据来源）
     */
    private void enqueueExplanationRequest(AlertRecordVo record) {
        if (!appProperties.getAi().getExplanation().isEnabled()) {
            return;
        }
        AiAlertExplanationEnvelopeFactory factory = explanationEnvelopeFactory.getIfAvailable();
        if (factory == null) {
            // 开关与装配条件不同步属配置错误：fail-closed 拒绝登记，事件不半途产生。
            log.warn("explanation enabled but envelope factory missing, skip enqueue, recordId={}", record.getId());
            return;
        }
        String eventId = UUID.randomUUID().toString();
        outboxService.enqueue(factory.EVENT_TYPE, factory.ROUTING_KEY, factory.build(record, eventId), eventId);
        log.debug("explanation request enqueued, recordId={}, eventId={}", record.getId(), eventId);
    }

    /**
     * 反序列化并校验信封；不可重试数据错误直接拒绝（进 DLQ）。
     */
    private AlertTriggeredMessage parseEnvelope(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        AlertTriggeredMessage envelope;
        try {
            envelope = objectMapper.readValue(body, AlertTriggeredMessage.class);
        } catch (JsonProcessingException exception) {
            log.warn("consume rejected: unparseable envelope");
            throw new AmqpRejectAndDontRequeueException("unparseable envelope", exception);
        }
        if (envelope.schemaVersion() != AlertTriggeredEnvelopeFactory.SCHEMA_VERSION
                || !AlertTriggeredEnvelopeFactory.EVENT_TYPE.equals(envelope.eventType())) {
            log.warn("consume rejected: unsupported schemaVersion={} or eventType={}",
                    envelope.schemaVersion(), envelope.eventType());
            throw new AmqpRejectAndDontRequeueException("unsupported schema version or event type");
        }
        try {
            messageValidator.validate(envelope);
        } catch (IllegalArgumentException exception) {
            log.warn("consume rejected: invalid alert triggered contract");
            throw new AmqpRejectAndDontRequeueException("invalid alert triggered contract", exception);
        }
        return envelope;
    }

    /** 插入消费幂等记录，已存在则翻转为 consumed。 */
    private void insertConsumeRecord(AlertTriggeredMessage envelope) {
        ConsumeRecordEntity record = new ConsumeRecordEntity();
        record.setConsumer(CONSUMER_NAME);
        record.setEventId(envelope.eventId());
        record.setStatus(ConsumeStatus.CONSUMED.ruleValue());
        record.setAttempts(0);
        record.setConsumedAt(LocalDateTime.now(clock));
        // upsert：若该事件此前失败留痕过（failed 行），重放成功后翻转回 consumed。
        consumeRecordMapper.upsertConsumed(record);
    }

    /** 判断规则是否配置了至少一个通知渠道。 */
    private boolean hasAnyChannel(AlertRuleEntity rule) {
        return StringUtils.hasText(rule.getNotifyEmail())
                || StringUtils.hasText(rule.getNotifyDingtalk())
                || StringUtils.hasText(rule.getNotifyWebhook());
    }

    /** 将 Entity 转换为 VO，时间字段转为 UTC OffsetDateTime（与评估器 toVo 语义一致）。 */
    private AlertRecordVo toVo(AlertRecordEntity entity) {
        AlertRecordVo vo = new AlertRecordVo();
        vo.setId(entity.getId());
        vo.setRuleId(entity.getRuleId());
        vo.setServerId(entity.getServerId());
        vo.setMetric(entity.getMetric());
        vo.setCurrentValue(entity.getCurrentValue());
        vo.setThresholdValue(entity.getThresholdValue());
        vo.setLevel(entity.getLevel());
        vo.setStatus(entity.getStatus());
        vo.setMessage(entity.getMessage());
        vo.setTriggeredAt(AlertRecordVo.toOffset(entity.getTriggeredAt()));
        vo.setResolvedAt(AlertRecordVo.toOffset(entity.getResolvedAt()));
        vo.setNotifiedAt(AlertRecordVo.toOffset(entity.getNotifiedAt()));
        vo.setNotifyChannels(entity.getNotifyChannels());
        vo.setCreatedAt(AlertRecordVo.toOffset(entity.getCreatedAt()));
        return vo;
    }

    /** 消费事务的排程结果：待发送的通知行 + 发送所需上下文（提交后供异步发送使用）。 */
    private record DispatchResult(Long recordId, AlertRuleEntity rule, AlertRecordVo record,
            List<AlertNotificationEntity> notifications) {
    }
}
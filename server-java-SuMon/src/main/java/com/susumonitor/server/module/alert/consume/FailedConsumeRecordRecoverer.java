package com.susumonitor.server.module.alert.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

/**
 * 消费失败留痕 recoverer（MVP-11 加固，2026-08-12 支持多消费者）：容器重试耗尽/
 * 不可重试拒绝时，在 reject 进 DLQ 之前尽力写入一条 failed 消费记录。
 *
 * <p>与 {@link ConsumeRecordMapper#upsertFailed} 配套，补齐"失败留痕"语义
 * （V15 枚举注释声明但此前从未落库）。consumer 名从消息的消费队列
 * （{@code MessageProperties.consumerQueue}）映射，两个监听器（metrics 评估 /
 * alert 出站通知）共用同一 recoverer bean，各自按队列落对应 consumer 名与
 * 信封类型。语义约定：</p>
 * <ul>
 *   <li>可解析出 event_id 才留痕——非法 JSON 无法定位事件，仅告警日志；</li>
 *   <li>不可重试数据错误（{@link AmqpRejectAndDontRequeueException} 链）记录
 *       attempts=1（零重试即拒）；其余重试耗尽记录配置的最大尝试次数；</li>
 *   <li>留痕为 best-effort：DB 异常只记日志，绝不阻断 reject 进 DLQ；</li>
 *   <li>failed 行不影响幂等查询（{@code existsConsumed} 仅认 consumed），
 *       DLQ 重放可重新处理该事件，成功后由 {@code upsertConsumed} 翻转回 consumed。</li>
 * </ul>
 */
@Slf4j
public class FailedConsumeRecordRecoverer implements MessageRecoverer {

    /** 与 V15 表定义一致：last_error VARCHAR(500)，不落敏感信息。 */
    static final int MAX_ERROR_LENGTH = 500;

    /** 队列 → 消费者名映射（与 @RabbitListener 消费的队列一一对应）。 */
    private static final java.util.Map<String, String> QUEUE_CONSUMER = java.util.Map.of(
            AlertMessageConsumer.QUEUE, AlertMessageConsumer.CONSUMER_NAME,
            AlertTriggeredConsumer.QUEUE, AlertTriggeredConsumer.CONSUMER_NAME,
            AlertResolvedConsumer.QUEUE, AlertResolvedConsumer.CONSUMER_NAME);

    /** 缺省消费者名：队列信息缺失时回退 metrics 评估消费（兼容旧行为）。 */
    private static final String DEFAULT_CONSUMER = AlertMessageConsumer.CONSUMER_NAME;

    private final ConsumeRecordMapper consumeRecordMapper;

    private final ObjectMapper objectMapper;

    /** 重试耗尽时的实际尝试次数（spring.rabbitmq.listener.simple.retry.max-attempts）。 */
    private final int maxAttempts;

    private final MessageRecoverer delegate;

    /** 构造失败留痕 recoverer，使用默认 RejectAndDontRequeueRecoverer 委托。 */
    public FailedConsumeRecordRecoverer(ConsumeRecordMapper consumeRecordMapper, ObjectMapper objectMapper,
            int maxAttempts) {
        this(consumeRecordMapper, objectMapper, maxAttempts, new RejectAndDontRequeueRecoverer());
    }

    /** 包私有构造：测试注入 mock 委托以断言 reject 行为。 */
    FailedConsumeRecordRecoverer(ConsumeRecordMapper consumeRecordMapper, ObjectMapper objectMapper,
            int maxAttempts, MessageRecoverer delegate) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
        this.delegate = delegate;
    }

    /** 先尽力记录失败留痕，再委托默认 recoverer reject 进 DLQ。 */
    @Override
    public void recover(Message message, Throwable cause) {
        recordFailure(message, cause);
        delegate.recover(message, cause);
    }

    /** 尽力写 failed 行；异常只记日志，不阻断后续 reject。 */
    private void recordFailure(Message message, Throwable cause) {
        String eventId = extractEventId(message);
        if (eventId == null) {
            log.warn("consume failure left no record: event_id not parseable, cause={}", rootMessage(cause));
            return;
        }
        try {
            consumeRecordMapper.upsertFailed(consumerFor(message), eventId,
                    isNonRetryable(cause) ? 1 : maxAttempts, truncate(rootMessage(cause)));
        } catch (Exception exception) {
            log.warn("consume failure record write failed, eventId={}", eventId, exception);
        }
    }

    /** 从消息体按队列对应的信封类型解析 event_id；解析失败返回 null（非法 JSON 无事件可定位）。 */
    private String extractEventId(Message message) {
        try {
            if (isAlertTriggeredQueue(message)) {
                AlertTriggeredMessage envelope = objectMapper.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8), AlertTriggeredMessage.class);
                return envelope.eventId();
            }
            if (isAlertResolvedQueue(message)) {
                AlertResolvedMessage envelope = objectMapper.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8), AlertResolvedMessage.class);
                return envelope.eventId();
            }
            MetricsReportedMessage envelope = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), MetricsReportedMessage.class);
            return envelope.eventId();
        } catch (Exception exception) {
            return null;
        }
    }

    /** 按消息的消费队列解析消费者名；队列信息缺失时回退缺省名。 */
    private String consumerFor(Message message) {
        String queue = message.getMessageProperties() == null ? null : message.getMessageProperties().getConsumerQueue();
        String consumer = queue == null ? null : QUEUE_CONSUMER.get(queue);
        return consumer == null ? DEFAULT_CONSUMER : consumer;
    }

    /** 判断消息是否来自 alert.triggered 队列（决定反序列化信封类型）。 */
    private boolean isAlertTriggeredQueue(Message message) {
        String queue = message.getMessageProperties() == null ? null : message.getMessageProperties().getConsumerQueue();
        return AlertTriggeredConsumer.QUEUE.equals(queue);
    }

    /** 判断消息是否来自 alert.resolved 队列（决定反序列化信封类型）。 */
    private boolean isAlertResolvedQueue(Message message) {
        String queue = message.getMessageProperties() == null ? null : message.getMessageProperties().getConsumerQueue();
        return AlertResolvedConsumer.QUEUE.equals(queue);
    }

    /** 与 AlertRabbitConfig 错误分类一致：cause 链含 AmqpRejectAndDontRequeueException 视为不可重试。 */
    /** 判断异常链中是否包含不可重试异常。 */
    private boolean isNonRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 取根因消息摘要：类名 + 消息，截断到 last_error 列长度。 */
    /** 取根因异常的消息摘要。 */
    private String rootMessage(Throwable cause) {
        Throwable current = cause;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getClass().getSimpleName()
                + (current.getMessage() != null ? ": " + current.getMessage() : "");
        return truncate(message);
    }

    /** 截断字符串到最大错误长度。 */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}

package com.susumonitor.server.module.alert.consume;

import com.susumonitor.server.module.ai.consume.AiAlertExplanationConsumer;
import java.util.Map;

/**
 * 队列 → 消费者名共享映射（rabbitmq-topology-v1.md §二）。
 *
 * <p>失败留痕 recoverer 与消费计时拦截器共用同一份映射，杜绝重复定义漂移；
 * 新消费者接入时必须同步扩展本映射与 {@code FailedConsumeRecordRecoverer}
 * 的信封解析分支（20260815 运维收口的教训，见 Develop-log/20260815）。</p>
 */
public final class ConsumerQueueNames {

    /** 队列 → 消费者名（与 @RabbitListener 消费的队列一一对应）。 */
    public static final Map<String, String> QUEUE_TO_CONSUMER = Map.of(
            AlertMessageConsumer.QUEUE, AlertMessageConsumer.CONSUMER_NAME,
            AlertTriggeredConsumer.QUEUE, AlertTriggeredConsumer.CONSUMER_NAME,
            AlertResolvedConsumer.QUEUE, AlertResolvedConsumer.CONSUMER_NAME,
            AiAlertExplanationConsumer.QUEUE, AiAlertExplanationConsumer.CONSUMER_NAME);

    /** 缺省消费者名：队列信息缺失时回退 metrics 评估消费（兼容旧行为）。 */
    public static final String DEFAULT_CONSUMER = AlertMessageConsumer.CONSUMER_NAME;

    private ConsumerQueueNames() {
    }
}

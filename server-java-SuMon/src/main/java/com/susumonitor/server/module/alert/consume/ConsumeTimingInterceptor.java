package com.susumonitor.server.module.alert.consume;

import java.time.Clock;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费处理耗时拦截器（MVP-14 监控收尾）。
 *
 * <p>作为 {@code SimpleRabbitListenerContainerFactory} adviceChain 的最外层，
 * 对 {@code onMessage(Message)} 整轮调用（含容器级重试循环）计时并写入
 * {@link ConsumeTimingStatsRegistry}；异常原样 rethrow，不吞业务异常。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class ConsumeTimingInterceptor implements MethodInterceptor {

    private final ConsumeTimingStatsRegistry registry;

    /** 注入耗时统计注册表。 */
    public ConsumeTimingInterceptor(ConsumeTimingStatsRegistry registry) {
        this.registry = registry;
    }

    /**
     * 计时并透传调用。
     *
     * @param invocation 监听方法调用（onMessage）
     * @return 业务方法返回值
     * @throws Throwable 业务异常原样传播
     */
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        long startedAt = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            registry.record(consumerFor(invocation), durationMs);
        }
    }

    /** 从方法入参消息的消费队列映射消费者名；无法识别时回退缺省名。 */
    private String consumerFor(MethodInvocation invocation) {
        for (Object argument : invocation.getArguments()) {
            if (argument instanceof Message message && message.getMessageProperties() != null) {
                return consumerFor(message.getMessageProperties());
            }
        }
        return ConsumerQueueNames.DEFAULT_CONSUMER;
    }

    /** 按消息属性中的消费队列映射消费者名。 */
    static String consumerFor(MessageProperties properties) {
        String queue = properties.getConsumerQueue();
        return queue == null ? ConsumerQueueNames.DEFAULT_CONSUMER
                : ConsumerQueueNames.QUEUE_TO_CONSUMER.getOrDefault(queue, ConsumerQueueNames.DEFAULT_CONSUMER);
    }
}

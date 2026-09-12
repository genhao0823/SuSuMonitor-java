package com.susumonitor.server.module.alert.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import java.time.Duration;

/**
 * 告警消费监听容器配置（MVP-11，对照冻结契约 rabbitmq-topology-v1.md §四）。
 *
 * <p>AUTO 确认模式下容器负责 ACK 与异常拒绝，配合容器级有限重试：</p>
 *
 * <ul>
 *   <li>错误分类器：{@link AmqpRejectAndDontRequeueException}（不可重试数据错误）
 *       立即走 recover，不做容器重试；</li>
 *   <li>其余异常按容器级有限重试（max-attempts 冻结 3 次 + 指数退避）；</li>
 *   <li>重试耗尽后由 recover 写入 failed 留痕并 reject（requeue=false），
 *       消息经 DLX 进 DLQ。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class AlertRabbitConfig {

    /**
     * 覆盖 Boot 自动容器工厂：AUTO 确认（容器在监听方法返回后 ACK，事务提交后才
     * 返回）+ 有限重试 + 耗尽后失败留痕并 reject 进 DLQ。
     *
     * <p>并发消费：concurrency 个消费者共享同一队列，幂等由 V15
     * message_consume_records 唯一键 + 业务事务保障（重复投递只消费一次）；</p>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxAttempts,
            @Value("${spring.rabbitmq.listener.simple.retry.initial-interval:1000ms}") Duration initialInterval,
            @Value("${spring.rabbitmq.listener.simple.retry.multiplier:2}") double multiplier,
            @Value("${spring.rabbitmq.listener.simple.retry.max-interval:10000ms}") Duration maxInterval,
            @Value("${spring.rabbitmq.listener.simple.concurrency:1}") int concurrency,
            @Value("${spring.rabbitmq.listener.simple.prefetch:1}") int prefetch,
            MessageRecoverer failedConsumeRecordRecoverer,
            ConsumeTimingInterceptor consumeTimingInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // 耗时拦截器置于最外层：记录含容器级重试循环的整轮处理耗时（MVP-14 监控收尾）。
        factory.setAdviceChain(consumeTimingInterceptor,
                buildRetryAdvice(maxAttempts, initialInterval.toMillis(), multiplier,
                        maxInterval.toMillis(), failedConsumeRecordRecoverer));
        if (concurrency > 1) {
            factory.setConcurrentConsumers(concurrency);
            factory.setMaxConcurrentConsumers(concurrency);
            factory.setPrefetchCount(prefetch);
        }
        return factory;
    }

    /**
     * 失败留痕 recoverer：重试耗尽/不可重试拒绝时先写 failed 消费记录（best-effort），
     * 再委托 {@code RejectAndDontRequeueRecoverer} reject 进 DLQ。
     */
    @Bean
    public MessageRecoverer failedConsumeRecordRecoverer(ConsumeRecordMapper consumeRecordMapper,
            ObjectMapper objectMapper,
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxAttempts) {
        return new FailedConsumeRecordRecoverer(consumeRecordMapper, objectMapper, maxAttempts);
    }

    /**
     * 有限重试拦截器：不可重试数据错误立即 recover，其余按退避重试。
     */
    /** 构建有限重试拦截器：不可重试数据错误立即 recover，其余按指数退避重试。 */
    private RetryOperationsInterceptor buildRetryAdvice(int maxAttempts, long initialIntervalMillis,
            double multiplier, long maxIntervalMillis, MessageRecoverer recoverer) {
        // 异常分类：AmqpRejectAndDontRequeueException（含 cause 链）不重试，其余有限重试。
        ExceptionClassifierRetryPolicy policy = new ExceptionClassifierRetryPolicy();
        policy.setExceptionClassifier(throwable -> {
            Throwable current = throwable;
            while (current != null) {
                if (current instanceof AmqpRejectAndDontRequeueException) {
                    return new NeverRetryPolicy();
                }
                current = current.getCause();
            }
            return new SimpleRetryPolicy(maxAttempts);
        });
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(initialIntervalMillis);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxIntervalMillis);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(policy);
        retryTemplate.setBackOffPolicy(backOff);
        return RetryInterceptorBuilder.stateless()
                .recoverer(recoverer)
                .retryOperations(retryTemplate)
                .build();
    }
}

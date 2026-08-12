package com.susumonitor.server.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 冻结的 RabbitMQ 拓扑声明（rabbitmq-topology-v1.md §二/§三）。
 *
 * <p>MVP-10 声明完整四件套：业务 Exchange、死信 Exchange、业务队列（带 DLX 参数）
 * 与死信队列。消息堆积在业务队列等待 MVP-11 消费者接入，不视为丢失。</p>
 *
 * <p>所有组件 durable + non-auto-delete；队列名不包含实例 ID。</p>
 */
@Configuration
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class RabbitMqTopologyConfig {

    static final String EVENTS_EXCHANGE = "susumonitor.events";
    static final String DLX_EXCHANGE = "susumonitor.dlx";
    static final String ALERT_METRICS_QUEUE = "susumonitor.alert.metrics";
    static final String ALERT_METRICS_DLQ = "susumonitor.alert.metrics.dlq";
    static final String METRICS_REPORTED_KEY = "metrics.reported.v1";
    static final String ALERT_TRIGGERED_QUEUE = "susumonitor.alert.triggered";
    static final String ALERT_TRIGGERED_DLQ = "susumonitor.alert.triggered.dlq";
    static final String ALERT_TRIGGERED_KEY = "alert.triggered.v1";

    /** 业务事件交换器。 */
    @Bean
    TopicExchange susumonitorEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    /** 死信交换器。 */
    @Bean
    TopicExchange susumonitorDlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    /** Alert 消费 metrics.reported.v1 的业务队列（重试耗尽后进 DLX）。 */
    @Bean
    Queue susumonitorAlertMetricsQueue() {
        return QueueBuilder.durable(ALERT_METRICS_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(METRICS_REPORTED_KEY)
                .build();
    }

    /** 死信队列，不自动回投业务队列。 */
    @Bean
    Queue susumonitorAlertMetricsDlq() {
        return QueueBuilder.durable(ALERT_METRICS_DLQ).build();
    }

    /**
     * alert.triggered.v1 业务队列（出站告警事件，供未来消费者/外部系统接入）。
     *
     * <p>告警事件由 Outbox 发布器按行 routing_key 路由至此；当前暂无消费者，
     * 消息堆积在业务队列等待消费者接入，不视为丢失（与 MVP-10 发布先行先例一致）。</p>
     */
    @Bean
    Queue susumonitorAlertTriggeredQueue() {
        return QueueBuilder.durable(ALERT_TRIGGERED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ALERT_TRIGGERED_KEY)
                .build();
    }

    /** alert.triggered.v1 死信队列，不自动回投业务队列。 */
    @Bean
    Queue susumonitorAlertTriggeredDlq() {
        return QueueBuilder.durable(ALERT_TRIGGERED_DLQ).build();
    }

    /** 业务队列绑定：susumonitor.events -- metrics.reported.v1 --> susumonitor.alert.metrics。 */
    @Bean
    Binding alertMetricsBinding() {
        return BindingBuilder.bind(susumonitorAlertMetricsQueue())
                .to(susumonitorEventsExchange()).with(METRICS_REPORTED_KEY);
    }

    /** 死信队列绑定：susumonitor.dlx -- metrics.reported.v1 --> susumonitor.alert.metrics.dlq。 */
    @Bean
    Binding alertMetricsDlqBinding() {
        return BindingBuilder.bind(susumonitorAlertMetricsDlq())
                .to(susumonitorDlxExchange()).with(METRICS_REPORTED_KEY);
    }

    /** 业务队列绑定：susumonitor.events -- alert.triggered.v1 --> susumonitor.alert.triggered。 */
    @Bean
    Binding alertTriggeredBinding() {
        return BindingBuilder.bind(susumonitorAlertTriggeredQueue())
                .to(susumonitorEventsExchange()).with(ALERT_TRIGGERED_KEY);
    }

    /** 死信队列绑定：susumonitor.dlx -- alert.triggered.v1 --> susumonitor.alert.triggered.dlq。 */
    @Bean
    Binding alertTriggeredDlqBinding() {
        return BindingBuilder.bind(susumonitorAlertTriggeredDlq())
                .to(susumonitorDlxExchange()).with(ALERT_TRIGGERED_KEY);
    }
}

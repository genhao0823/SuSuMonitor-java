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

    public static final String EVENTS_EXCHANGE = "susumonitor.events";
    public static final String DLX_EXCHANGE = "susumonitor.dlx";
    public static final String ALERT_METRICS_QUEUE = "susumonitor.alert.metrics";
    public static final String ALERT_METRICS_DLQ = "susumonitor.alert.metrics.dlq";
    public static final String METRICS_REPORTED_KEY = "metrics.reported.v1";
    public static final String ALERT_TRIGGERED_QUEUE = "susumonitor.alert.triggered";
    public static final String ALERT_TRIGGERED_DLQ = "susumonitor.alert.triggered.dlq";
    public static final String ALERT_TRIGGERED_KEY = "alert.triggered.v1";
    public static final String ALERT_RESOLVED_QUEUE = "susumonitor.alert.resolved";
    public static final String ALERT_RESOLVED_DLQ = "susumonitor.alert.resolved.dlq";
    public static final String ALERT_RESOLVED_KEY = "alert.resolved.v1";

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

    /**
     * alert.resolved.v1 业务队列（出站恢复事件，消费者 alert-resolved-notifier 已接入，
     * 驱动"恢复通知"排程；与 alert.triggered.v1 对称）。
     */
    @Bean
    Queue susumonitorAlertResolvedQueue() {
        return QueueBuilder.durable(ALERT_RESOLVED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ALERT_RESOLVED_KEY)
                .build();
    }

    /** alert.resolved.v1 死信队列，不自动回投业务队列。 */
    @Bean
    Queue susumonitorAlertResolvedDlq() {
        return QueueBuilder.durable(ALERT_RESOLVED_DLQ).build();
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

    /** 业务队列绑定：susumonitor.events -- alert.resolved.v1 --> susumonitor.alert.resolved。 */
    @Bean
    Binding alertResolvedBinding() {
        return BindingBuilder.bind(susumonitorAlertResolvedQueue())
                .to(susumonitorEventsExchange()).with(ALERT_RESOLVED_KEY);
    }

    /** 死信队列绑定：susumonitor.dlx -- alert.resolved.v1 --> susumonitor.alert.resolved.dlq。 */
    @Bean
    Binding alertResolvedDlqBinding() {
        return BindingBuilder.bind(susumonitorAlertResolvedDlq())
                .to(susumonitorDlxExchange()).with(ALERT_RESOLVED_KEY);
    }
}

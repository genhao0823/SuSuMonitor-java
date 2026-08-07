package com.susumonitor.server.config;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 发布侧定制（MVP-10 Outbox）。
 *
 * <p>连接参数走标准 {@code spring.rabbitmq.*}（starter 自动配置）；
 * 本类只开启 Publisher Confirm（CORRELATED）与 Return 回退，
 * 并强制 RabbitTemplate mandatory=true——不可路由的消息必须回退感知，
 * 不能静默丢弃（冻结拓扑的绑定存在时不会发生，属防御性保障）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class RabbitMqConfig {

    /**
     * 在自动配置的 CachingConnectionFactory 上开启 Publisher Confirm 与 Return。
     *
     * <p>连接工厂懒建连，afterInitialization 阶段设置两项能力是安全的；
     * 开启后 CorrelationData 即可通过 future 同步读取 Confirm 结果。</p>
     *
     * @return 连接工厂后处理器
     */
    @Bean
    static BeanPostProcessor publisherConfirmBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof CachingConnectionFactory cachingFactory) {
                    cachingFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
                    cachingFactory.setPublisherReturns(true);
                }
                return bean;
            }
        };
    }

    /**
     * RabbitTemplate 强制 mandatory 模式；Confirm 结果通过 CorrelationData future
     * 由发布服务同步读取，此处仅记录不可路由消息（防御性留痕，不落敏感内容）。
     *
     * @return 模板定制器
     */
    @Bean
    org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer mandatoryTemplateCustomizer() {
        return template -> {
            template.setMandatory(true);
            template.setReturnsCallback(returned -> logUnroutable(returned));
        };
    }

    /**
     * 记录不可路由的 Outbox 消息到日志，仅包含 Exchange 和 Routing Key 信息。
     *
     * @param returned 不可路由的返回消息
     */
    private void logUnroutable(org.springframework.amqp.core.ReturnedMessage returned) {
        org.slf4j.LoggerFactory.getLogger(RabbitMqConfig.class).warn(
                "outbox message unroutable, exchange={}, routingKey={}",
                returned.getExchange(), returned.getRoutingKey());
    }
}

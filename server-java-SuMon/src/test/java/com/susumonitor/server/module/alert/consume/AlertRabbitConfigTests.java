package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

/**
 * 验证告警消费容器工厂的并发参数装配：concurrency &gt; 1 时启用多消费者与预取，
 * 默认保持单消费者（兼容既有部署行为）。
 *
 * <p>并发字段定义在 {@code AbstractRabbitListenerContainerFactory}（setter-only），
 * 测试经反射读取。</p>
 */
class AlertRabbitConfigTests {

    private final AlertRabbitConfig config = new AlertRabbitConfig();

    private SimpleRabbitListenerContainerFactory buildFactory(int concurrency, int prefetch) {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        MessageRecoverer recoverer = mock(MessageRecoverer.class);
        ConsumeTimingInterceptor timingInterceptor = mock(ConsumeTimingInterceptor.class);
        return config.rabbitListenerContainerFactory(
                connectionFactory,
                3,
                Duration.ofMillis(1000),
                2.0,
                Duration.ofSeconds(10),
                concurrency,
                prefetch,
                recoverer,
                timingInterceptor);
    }

    /** 读取并发字段：concurrentConsumers/maxConcurrentConsumers 在工厂类，prefetchCount 在父类。 */
    private static Object readField(SimpleRabbitListenerContainerFactory factory, String name) throws Exception {
        Class<?> type = SimpleRabbitListenerContainerFactory.class;
        Field field;
        try {
            field = type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            field = type.getSuperclass().getDeclaredField(name);
        }
        field.setAccessible(true);
        return field.get(factory);
    }

    /** 默认 concurrency=1：保持单消费者，不设置并发/预取（与历史行为一致）。 */
    @Test
    void defaultConcurrencyKeepsSingleConsumer() throws Exception {
        SimpleRabbitListenerContainerFactory factory = buildFactory(1, 1);
        assertNotNull(factory);
        assertNull(readField(factory, "concurrentConsumers"));
        assertNull(readField(factory, "prefetchCount"));
    }

    /** concurrency=4：并发消费者与预取均生效。 */
    @Test
    void concurrencyAboveOneEnablesMultiConsumer() throws Exception {
        SimpleRabbitListenerContainerFactory factory = buildFactory(4, 2);
        assertEquals(4, readField(factory, "concurrentConsumers"));
        assertEquals(4, readField(factory, "maxConcurrentConsumers"));
        assertEquals(2, readField(factory, "prefetchCount"));
    }
}

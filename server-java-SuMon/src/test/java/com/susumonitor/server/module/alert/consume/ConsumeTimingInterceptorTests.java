package com.susumonitor.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 验证消费耗时拦截器计时、异常透传与消费者名映射。
 */
class ConsumeTimingInterceptorTests {

    private ConsumeTimingStatsRegistry registry;

    private ConsumeTimingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new ConsumeTimingStatsRegistry(Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC));
        interceptor = new ConsumeTimingInterceptor(registry);
    }

    /** 成功调用应记录该消费者一次耗时且透传返回值。 */
    @Test
    void successfulInvocationShouldRecordTiming() throws Throwable {
        MethodInvocation invocation = invocation(AlertMessageConsumer.QUEUE, "done");

        Object result = interceptor.invoke(invocation);

        assertEquals("done", result);
        Map<String, ConsumeTimingStatsRegistry.ConsumerTimingSnapshot> snapshot = registry.snapshot();
        assertEquals(1, snapshot.size());
        ConsumeTimingStatsRegistry.ConsumerTimingSnapshot stats = snapshot.get(AlertMessageConsumer.CONSUMER_NAME);
        assertEquals(1, stats.totalCount());
        assertTrue(stats.totalMs() >= 0);
        assertTrue(stats.lastSampleAt() != null);
    }

    /** 异常应原样 rethrow，同时仍记录耗时。 */
    @Test
    void failingInvocationShouldRethrowAndRecordTiming() throws Throwable {
        MethodInvocation invocation = invocation(AlertTriggeredConsumer.QUEUE, null);
        when(invocation.proceed()).thenThrow(new IllegalStateException("db unavailable"));

        assertThrows(IllegalStateException.class, () -> interceptor.invoke(invocation));
        ConsumeTimingStatsRegistry.ConsumerTimingSnapshot stats =
                registry.snapshot().get(AlertTriggeredConsumer.CONSUMER_NAME);
        assertEquals(1, stats.totalCount());
    }

    /** 三个业务队列各自映射到正确消费者名。 */
    @Test
    void consumerNameShouldFollowQueueMapping() throws Throwable {
        interceptor.invoke(invocation(AlertMessageConsumer.QUEUE, null));
        interceptor.invoke(invocation(AlertTriggeredConsumer.QUEUE, null));
        interceptor.invoke(invocation(AlertResolvedConsumer.QUEUE, null));

        Map<String, ConsumeTimingStatsRegistry.ConsumerTimingSnapshot> snapshot = registry.snapshot();
        assertEquals(3, snapshot.size());
        assertTrue(snapshot.containsKey(AlertMessageConsumer.CONSUMER_NAME));
        assertTrue(snapshot.containsKey(AlertTriggeredConsumer.CONSUMER_NAME));
        assertTrue(snapshot.containsKey(AlertResolvedConsumer.CONSUMER_NAME));
    }

    /** 队列信息缺失或未知时回退缺省消费者名。 */
    @Test
    void missingQueueShouldFallBackToDefaultConsumer() throws Throwable {
        interceptor.invoke(invocation(null, null));

        assertTrue(registry.snapshot().containsKey(ConsumerQueueNames.DEFAULT_CONSUMER));
    }

    /** 构造带指定消费队列的 onMessage 方法调用。 */
    private MethodInvocation invocation(String queue, Object returnValue) throws Throwable {
        MethodInvocation invocation = mock(MethodInvocation.class);
        MessageProperties properties = new MessageProperties();
        if (queue != null) {
            properties.setConsumerQueue(queue);
        }
        when(invocation.getArguments()).thenReturn(new Object[] { new Message(new byte[0], properties) });
        when(invocation.proceed()).thenReturn(returnValue);
        return invocation;
    }
}

package com.susumonitor.server.module.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.AMQP.Queue.DeclareOk;
import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 验证队列积压探测：快照更新、阈值告警路径与单队列失败隔离。
 */
class QueueBacklogProbeServiceTests {

    private RabbitTemplate rabbitTemplate;

    private QueueBacklogSnapshotRegistry registry;

    private AppProperties appProperties;

    private Channel channel;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        registry = new QueueBacklogSnapshotRegistry();
        appProperties = new AppProperties();
        appProperties.getRabbitmq().setQueueBacklogWarnThreshold(10000);
        channel = mock(Channel.class);
        when(rabbitTemplate.execute(any(ChannelCallback.class))).thenAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });
    }

    private QueueBacklogProbeService newService() {
        return new QueueBacklogProbeService(rabbitTemplate, registry, appProperties,
                Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC));
    }

    /** 构造带指定消息数的被动声明结果（DeclareOk 为抽象类，用 mock 承载）。 */
    private DeclareOk declareOk(String queue, long messages) throws Exception {
        DeclareOk ok = mock(DeclareOk.class);
        when(ok.getMessageCount()).thenReturn((int) messages);
        return ok;
    }

    /** 探测应读取全部 6 个队列消息数并整体替换快照。 */
    @Test
    void probeShouldSnapshotAllQueues() throws Exception {
        Map<String, DeclareOk> responses = new java.util.HashMap<>();
        for (String queue : QueueBacklogProbeService.QUEUES) {
            responses.put(queue, declareOk(queue, 5));
        }
        for (Map.Entry<String, DeclareOk> entry : responses.entrySet()) {
            when(channel.queueDeclarePassive(entry.getKey())).thenReturn(entry.getValue());
        }

        newService().probe();

        Map<String, QueueBacklogSnapshotRegistry.QueueSnapshot> snapshot = registry.snapshot();
        assertEquals(6, snapshot.size());
        QueueBacklogSnapshotRegistry.QueueSnapshot metrics = snapshot.get("susumonitor.alert.metrics");
        assertEquals(5, metrics.messages());
        assertFalse(metrics.error());
        assertTrue(metrics.checkedAt() != null);
    }

    /** 单队列探测失败应标记 error 且不中断其余队列。 */
    @Test
    void probeShouldIsolateSingleQueueFailure() throws Exception {
        Map<String, DeclareOk> responses = new java.util.HashMap<>();
        for (String queue : QueueBacklogProbeService.QUEUES) {
            if (!queue.equals("susumonitor.alert.metrics")) {
                responses.put(queue, declareOk(queue, 1));
            }
        }
        when(channel.queueDeclarePassive("susumonitor.alert.metrics"))
                .thenThrow(new java.io.IOException("channel closed"));
        for (Map.Entry<String, DeclareOk> entry : responses.entrySet()) {
            when(channel.queueDeclarePassive(entry.getKey())).thenReturn(entry.getValue());
        }

        newService().probe();

        Map<String, QueueBacklogSnapshotRegistry.QueueSnapshot> snapshot = registry.snapshot();
        assertEquals(6, snapshot.size());
        assertTrue(snapshot.get("susumonitor.alert.metrics").error());
        assertFalse(snapshot.get("susumonitor.alert.triggered").error());
        assertEquals(1, snapshot.get("susumonitor.alert.triggered").messages());
    }

    /** 业务队列超阈值应触发 backlog 告警日志路径（快照仍正常记录）。 */
    @Test
    void probeShouldRecordBacklogExceedingThreshold() throws Exception {
        Map<String, DeclareOk> responses = new java.util.HashMap<>();
        responses.put("susumonitor.alert.metrics", declareOk("susumonitor.alert.metrics", 10001));
        for (String queue : QueueBacklogProbeService.QUEUES) {
            if (!queue.equals("susumonitor.alert.metrics")) {
                responses.put(queue, declareOk(queue, 0));
            }
        }
        for (Map.Entry<String, DeclareOk> entry : responses.entrySet()) {
            when(channel.queueDeclarePassive(entry.getKey())).thenReturn(entry.getValue());
        }

        newService().probe();

        assertEquals(10001, registry.snapshot().get("susumonitor.alert.metrics").messages());
    }
}

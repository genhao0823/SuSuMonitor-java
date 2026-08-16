package com.susumonitor.server.module.system;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.config.RabbitMqTopologyConfig;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 队列积压探测（MVP-14 监控收尾，零新依赖）。
 *
 * <p>通过 AMQP 被动声明 {@code queueDeclarePassive} 读取各队列消息数——无副作用、
 * 不触发队列声明变更；对 6 个冻结队列逐个探测，单队列失败只标记 error 不中断。
 * 业务队列（3 个）消息数超过阈值时输出 backlog warn 日志；死信队列仅记录快照
 * （死信处置见 RabbitMQ 运维手册 §死信处置）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class QueueBacklogProbeService {

    /** 冻结拓扑的全部队列（业务队列在前，死信队列在后，与 rabbitmq-topology-v1.md §二一致）。 */
    static final List<String> QUEUES = List.of(
            RabbitMqTopologyConfig.ALERT_METRICS_QUEUE,
            RabbitMqTopologyConfig.ALERT_TRIGGERED_QUEUE,
            RabbitMqTopologyConfig.ALERT_RESOLVED_QUEUE,
            RabbitMqTopologyConfig.ALERT_METRICS_DLQ,
            RabbitMqTopologyConfig.ALERT_TRIGGERED_DLQ,
            RabbitMqTopologyConfig.ALERT_RESOLVED_DLQ);

    /** 业务队列名集合（超阈值才告警）。 */
    public static final List<String> BUSINESS_QUEUES = List.of(
            RabbitMqTopologyConfig.ALERT_METRICS_QUEUE,
            RabbitMqTopologyConfig.ALERT_TRIGGERED_QUEUE,
            RabbitMqTopologyConfig.ALERT_RESOLVED_QUEUE);

    private final RabbitTemplate rabbitTemplate;

    private final QueueBacklogSnapshotRegistry registry;

    private final AppProperties appProperties;

    private final Clock clock;

    /** 注入模板、快照注册表、配置与应用时钟。 */
    public QueueBacklogProbeService(RabbitTemplate rabbitTemplate, QueueBacklogSnapshotRegistry registry,
            AppProperties appProperties, Clock clock) {
        this.rabbitTemplate = rabbitTemplate;
        this.registry = registry;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    /** 探测全部冻结队列并整体替换快照；Broker 不可达时标记全部队列 error。 */
    public void probe() {
        OffsetDateTime checkedAt = OffsetDateTime.now(clock);
        Map<String, QueueBacklogSnapshotRegistry.QueueSnapshot> latest = new HashMap<>();
        for (String queue : QUEUES) {
            latest.put(queue, probeQueue(queue, checkedAt));
        }
        registry.replaceAll(latest);
        warnBacklog(latest);
    }

    /** 探测单个队列；异常时返回 error 快照，不向上传播。 */
    private QueueBacklogSnapshotRegistry.QueueSnapshot probeQueue(String queue, OffsetDateTime checkedAt) {
        try {
            long messages = rabbitTemplate.execute(channel ->
                    channel.queueDeclarePassive(queue).getMessageCount());
            return new QueueBacklogSnapshotRegistry.QueueSnapshot(queue, messages, checkedAt, false);
        } catch (Exception exception) {
            log.warn("queue backlog probe failed, queue={}, reason={}", queue, exception.getMessage());
            return new QueueBacklogSnapshotRegistry.QueueSnapshot(queue, 0, checkedAt, true);
        }
    }

    /** 业务队列超阈值时输出 backlog 告警日志。 */
    private void warnBacklog(Map<String, QueueBacklogSnapshotRegistry.QueueSnapshot> latest) {
        long threshold = appProperties.getRabbitmq().getQueueBacklogWarnThreshold();
        for (String queue : BUSINESS_QUEUES) {
            QueueBacklogSnapshotRegistry.QueueSnapshot snapshot = latest.get(queue);
            if (snapshot != null && !snapshot.error() && snapshot.messages() > threshold) {
                log.warn("queue backlog exceeded, queue={}, messages={}, threshold={}",
                        queue, snapshot.messages(), threshold);
            }
        }
    }
}

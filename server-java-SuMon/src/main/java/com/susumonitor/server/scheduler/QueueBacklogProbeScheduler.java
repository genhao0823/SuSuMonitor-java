package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.system.QueueBacklogProbeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按配置定时触发 RabbitMQ 队列积压探测，不直接访问 Broker 或 Mapper。
 */
@Slf4j
@Component
@ConditionalOnExpression("${susumonitor.rabbitmq.enabled:true} and ${susumonitor.rabbitmq.queue-monitor-enabled:true}")
public class QueueBacklogProbeScheduler {

    private final QueueBacklogProbeService probeService;

    /** 构造队列积压探测调度器。 */
    public QueueBacklogProbeScheduler(QueueBacklogProbeService probeService) {
        this.probeService = probeService;
    }

    /** 执行一轮队列积压探测；异常只影响当前轮次。 */
    @Scheduled(fixedDelayString = "${susumonitor.rabbitmq.queue-monitor-interval-ms:60000}")
    public void probeQueueBacklog() {
        try {
            probeService.probe();
        } catch (RuntimeException exception) {
            log.error("Queue backlog probe failed", exception);
        }
    }
}

package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.metrics.outbox.OutboxPublisherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按固定延迟轮询 Outbox 待发布事件；单轮失败不影响后续调度。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.rabbitmq.enabled", havingValue = "true")
public class OutboxPublisherScheduler {

    private final OutboxPublisherService outboxPublisherService;

    /** 构造 Outbox 发布调度器。 */
    public OutboxPublisherScheduler(OutboxPublisherService outboxPublisherService) {
        this.outboxPublisherService = outboxPublisherService;
    }

    /**
     * 执行一轮发布；Broker 不可达时退避重试，异常只影响当前轮次。
     */
    @Scheduled(fixedDelayString = "${susumonitor.rabbitmq.poll-interval-ms:200}")
    public void publishPendingEvents() {
        try {
            int published = outboxPublisherService.publishOnce();
            if (published > 0) {
                log.info("outbox published {} event(s)", published);
            }
        } catch (RuntimeException exception) {
            log.error("Outbox publish round failed", exception);
        }
    }
}

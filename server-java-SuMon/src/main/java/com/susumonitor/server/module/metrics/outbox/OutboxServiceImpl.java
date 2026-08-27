package com.susumonitor.server.module.metrics.outbox;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * Outbox 登记实现：登记调用方构建好的冻结事件信封为待发布行。
 *
 * <p>本类不开启独立事务，由调用方（指标写入事务、告警评估事务）保证
 * 业务写入与 outbox 同事务提交或回滚。事件 ID 由调用方生成并写入信封，
 * 本类不重复生成，保证行内 event_id 与 payload 中 event_id 恒一致。</p>
 */
@Service
public class OutboxServiceImpl implements OutboxService {

    private final OutboxMapper outboxMapper;

    private final Clock clock;

    /** 注入 Outbox 数据访问与应用时钟。 */
    public OutboxServiceImpl(OutboxMapper outboxMapper, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public void enqueue(String eventType, String routingKey, String payload, String eventId) {
        OutboxEntity outbox = new OutboxEntity();
        outbox.setEventId(eventId);
        outbox.setEventType(eventType);
        outbox.setRoutingKey(routingKey);
        outbox.setPayload(payload);
        outbox.setStatus(OutboxStatus.PENDING.ruleValue());
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(LocalDateTime.now(clock));
        outboxMapper.insert(outbox);
    }
}

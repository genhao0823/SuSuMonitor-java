package com.susumonitor.server.module.metrics.outbox;

/**
 * Outbox 业务契约：在业务写入事务内登记一条待可靠投递的冻结事件。
 *
 * <p>支持多事件类型共存：event_type 为逻辑事件名（契约 event_type），
 * routing_key 为目标路由键（契约版本化名，如 metrics.reported.v1），
 * 发布器按行路由到对应业务队列。</p>
 */
public interface OutboxService {

    /**
     * 登记待发布事件（必须在调用方事务内执行，与业务写入同事务提交）。
     *
     * @param eventType  契约逻辑事件名，如 metrics.reported
     * @param routingKey 目标 routing key（版本化名，如 metrics.reported.v1）
     * @param payload    冻结信封 JSON（message-contracts-v1 格式）
     * @param eventId    事件 UUID（消费侧幂等主键，须与 payload 中 event_id 一致）
     */
    void enqueue(String eventType, String routingKey, String payload, String eventId);
}

-- V25: message_outbox 增加 routing_key 列，支持多事件类型按行路由发布
-- Outbox 发布器原先使用全局单一 routing key（metrics.reported.v1）发布所有行；
-- alert.triggered.v1 契约落地后同一 exchange 需要按事件类型路由到不同业务队列，
-- 因此将 routing key 下沉为每行字段，现有行默认 metrics.reported.v1（语义正确，
-- 历史行均为 Metrics 事件）。
ALTER TABLE message_outbox
    ADD COLUMN routing_key VARCHAR(64) NOT NULL DEFAULT 'metrics.reported.v1'
        COMMENT '目标 routing key（事件契约版本化名，如 metrics.reported.v1 / alert.triggered.v1）' AFTER event_type;

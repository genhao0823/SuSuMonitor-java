-- 已确认发布的 Outbox 记录按 published_at 保留；pending 重试事件永不由该索引清理。
CREATE INDEX idx_outbox_published_retention
    ON message_outbox (status, published_at, id);

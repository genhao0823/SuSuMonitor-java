-- 为无保留期清理的增长表补充清理时间索引：
-- metrics_ingestions / message_consume_records 按 created_at（接收时间）分批删除；
-- alert_records 按 triggered_at（业务时间）分批删除。
CREATE INDEX idx_metrics_ingestions_created_at
    ON metrics_ingestions (created_at);

CREATE INDEX idx_message_consume_records_created_at
    ON message_consume_records (created_at);

CREATE INDEX idx_alert_records_triggered_at
    ON alert_records (triggered_at);

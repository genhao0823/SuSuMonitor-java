-- 告警恢复时间列：评估器 Resolve 时写入，恢复事件契约（alert.resolved.v1）payload 的 resolved_at 来源。
ALTER TABLE alert_records ADD COLUMN resolved_at DATETIME NULL;

-- 通知投递表清理索引：按 created_at 分批删除超过保留期的投递记录（V21 仅有 record/channel 与重试索引）。
ALTER TABLE alert_notifications ADD INDEX idx_created_at (created_at);

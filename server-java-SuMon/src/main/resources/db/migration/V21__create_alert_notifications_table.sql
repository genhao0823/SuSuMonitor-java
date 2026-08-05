CREATE TABLE `alert_notifications` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `alert_record_id` BIGINT UNSIGNED NOT NULL COMMENT '关联告警记录',
    `channel` VARCHAR(20) NOT NULL COMMENT 'email/dingtalk/webhook',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/sent/failed',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_attempt_at` DATETIME(6) NULL COMMENT '下次重试时间，null=不再重试',
    `last_error` VARCHAR(500) NULL COMMENT '最近失败原因（截断 500）',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_record_channel` (`alert_record_id`, `channel`),
    KEY `idx_next_attempt` (`status`, `next_attempt_at`)
) COMMENT '告警外部通知投递记录，每渠道一行，支持退避重试';

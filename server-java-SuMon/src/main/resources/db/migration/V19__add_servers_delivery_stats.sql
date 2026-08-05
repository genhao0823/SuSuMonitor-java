ALTER TABLE `servers`
    ADD COLUMN `delivery_pending_count` BIGINT DEFAULT NULL COMMENT 'Agent 投递积压条数' AFTER `last_heartbeat_at`,
    ADD COLUMN `delivery_pending_bytes` BIGINT DEFAULT NULL COMMENT 'Agent 投递积压字节数' AFTER `delivery_pending_count`,
    ADD COLUMN `delivery_oldest_collected_at` DATETIME(6) DEFAULT NULL COMMENT 'Agent 积压最旧采样时间' AFTER `delivery_pending_bytes`,
    ADD COLUMN `delivery_drop_count` BIGINT DEFAULT NULL COMMENT 'Agent 因缓冲满丢弃的采样计数' AFTER `delivery_oldest_collected_at`,
    ADD COLUMN `delivery_dead_letter_count` BIGINT DEFAULT NULL COMMENT 'Agent 本地死信条数' AFTER `delivery_drop_count`,
    ADD COLUMN `delivery_dead_letter_bytes` BIGINT DEFAULT NULL COMMENT 'Agent 本地死信字节数' AFTER `delivery_dead_letter_count`;

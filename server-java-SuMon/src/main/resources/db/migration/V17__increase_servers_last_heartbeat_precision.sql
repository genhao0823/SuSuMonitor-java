ALTER TABLE `servers`
    MODIFY COLUMN `last_heartbeat_at` DATETIME(6) DEFAULT NULL COMMENT '最后心跳时间';

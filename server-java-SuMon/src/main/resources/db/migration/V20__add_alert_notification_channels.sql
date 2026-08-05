ALTER TABLE `alert_rules`
    ADD COLUMN `notify_email`    VARCHAR(500) DEFAULT NULL COMMENT '通知邮件地址，多个用英文逗号分隔' AFTER `confirm_count`,
    ADD COLUMN `notify_dingtalk` VARCHAR(500) DEFAULT NULL COMMENT '钉钉机器人 Webhook URL' AFTER `notify_email`,
    ADD COLUMN `notify_webhook`  VARCHAR(500) DEFAULT NULL COMMENT '自定义 Webhook URL（POST JSON）' AFTER `notify_dingtalk`;

ALTER TABLE `alert_records`
    ADD COLUMN `notified_at`     DATETIME(6)  DEFAULT NULL COMMENT '外部通知发送完成时间' AFTER `triggered_at`,
    ADD COLUMN `notify_channels` VARCHAR(100) DEFAULT NULL COMMENT '已发出通知的渠道（email/dingtalk/webhook）' AFTER `notified_at`;

-- V37：首管理员一次性初始化令牌（批次 8，2026-09-16）
-- 背景：现行注册在 admin_initialized=0 时首个注册者直接提权 admin/approved，
--       公网部署存在「先到先得抢管理员」窗口；本迁移为初始化状态行增加令牌列，
--       注册链路在首管理员未初始化时强制校验一次性令牌（AES-256-GCM 密文落库）。
-- 兼容性：只加列不回填——已初始化实例（admin_initialized=1）三列保持 NULL，
--       运行时在 admin_initialized=1 分支永不读取，行为与历史版本完全一致。
ALTER TABLE `auth_bootstrap_state`
    ADD COLUMN `bootstrap_token_cipher` VARCHAR(512) DEFAULT NULL
        COMMENT '一次性初始化令牌 AES-256-GCM 密文信封（v1: 前缀），消费后置空' AFTER `initialized_at`,
    ADD COLUMN `bootstrap_token_generated_at` DATETIME DEFAULT NULL
        COMMENT '当前令牌生成或载入时间（UTC）' AFTER `bootstrap_token_cipher`,
    ADD COLUMN `bootstrap_token_consumed_at` DATETIME DEFAULT NULL
        COMMENT '令牌消费时间（首管理员创建时刻，UTC）' AFTER `bootstrap_token_generated_at`;

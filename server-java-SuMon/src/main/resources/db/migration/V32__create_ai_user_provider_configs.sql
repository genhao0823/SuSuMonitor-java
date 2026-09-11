-- 管理员个人 AI 服务商配置（按用户维度，一人一行）。
-- api_key 使用 AES-256-GCM 加密信封（v1: 前缀，AAD 绑定 user_id），绝不存明文；
-- base_url/model 用于按用户构造独立 OpenAI 兼容 provider 实例。
CREATE TABLE ai_user_provider_configs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(64) NOT NULL DEFAULT 'openai-compatible',
    base_url VARCHAR(512) NOT NULL,
    api_key_ciphertext VARCHAR(1024) NOT NULL,
    model VARCHAR(128) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_user_provider_configs_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员个人 AI 服务商配置';

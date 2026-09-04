-- AI 告警智能解释存储；每条告警记录至多一条成功解释（1:1 唯一键），
-- 不保存原始 prompt、告警展示文本、凭据或供应商原始响应。
CREATE TABLE ai_alert_explanations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_record_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    summary VARCHAR(4000) NOT NULL,
    result_json MEDIUMTEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    duration_ms BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_alert_explanations_record (alert_record_id),
    INDEX idx_ai_alert_explanations_created_at (created_at),
    INDEX idx_ai_alert_explanations_server_created (server_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 告警智能解释';

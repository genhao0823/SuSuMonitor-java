-- 只读 AI 诊断最小审计记录；不保存原始问题、完整 prompt、凭据或供应商原始响应。
CREATE TABLE ai_diagnostic_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NULL,
    correlation_id VARCHAR(64) NULL,
    actor_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    context_hash CHAR(64) NOT NULL,
    result_json MEDIUMTEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    duration_ms BIGINT NULL,
    error_code INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME NULL,
    INDEX idx_ai_diagnostic_runs_created_at (created_at),
    INDEX idx_ai_diagnostic_runs_actor_created (actor_id, created_at),
    INDEX idx_ai_diagnostic_runs_server_created (server_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 只读诊断最小审计记录';

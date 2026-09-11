-- AI 运维问答（F2）最小审计记录；不保存原始问题、完整 prompt、凭据或供应商原始响应。
-- question_hash 代替原文（与 V28 诊断审计同一最小化口径）；tool_calls_json 记录模型
-- 实际调用的只读工具名与受控参数概要（调用审计），供安全回溯与滥用排查。
CREATE TABLE ai_qa_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NULL,
    correlation_id VARCHAR(64) NULL,
    actor_id BIGINT NOT NULL,
    server_id BIGINT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    question_hash CHAR(64) NOT NULL,
    tool_calls_json TEXT NULL,
    result_json MEDIUMTEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    duration_ms BIGINT NULL,
    error_code INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME NULL,
    INDEX idx_ai_qa_runs_created_at (created_at),
    INDEX idx_ai_qa_runs_actor_created (actor_id, created_at),
    INDEX idx_ai_qa_runs_server_created (server_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 运维问答最小审计记录';

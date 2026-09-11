-- AI 定时健康报告存储（F3）；每个自然日至多一份报告（1:1 唯一键，重复生成走 UPSERT），
-- 不保存原始 prompt、服务器地址、凭据或供应商原始响应。
CREATE TABLE ai_health_reports (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    summary VARCHAR(4000) NULL,
    result_json MEDIUMTEXT NULL,
    error_code INT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    total_tokens INT NULL,
    duration_ms BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_health_reports_date (report_date),
    INDEX idx_ai_health_reports_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 定时健康报告';

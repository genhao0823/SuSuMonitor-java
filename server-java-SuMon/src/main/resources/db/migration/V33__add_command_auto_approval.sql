-- V33：AI 命令域自动审批
-- 1) ai_command_runs 补审计列：risk_level 为创建时模板风险等级快照，
--    approval_mode 区分人工审批（manual）与策略自动审批（auto，approver_id 保持 NULL）。
ALTER TABLE ai_command_runs
    ADD COLUMN risk_level VARCHAR(8) NOT NULL DEFAULT 'low'
        COMMENT '创建时模板风险等级快照：low/medium/high' AFTER source,
    ADD COLUMN approval_mode VARCHAR(12) NOT NULL DEFAULT 'manual'
        COMMENT '审批方式：manual 人工 / auto 策略自动' AFTER risk_level;

-- 2) 实例级自动审批策略（单行，id 固定 1；默认禁用 = 行为与 V29 以来完全一致）。
CREATE TABLE ai_command_auto_approval_policies (
    id TINYINT NOT NULL PRIMARY KEY COMMENT '固定为 1 的单行策略',
    enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用自动审批',
    max_risk_level VARCHAR(8) NOT NULL DEFAULT 'medium' COMMENT '自动审批风险阈值：low/medium',
    updated_by BIGINT NULL COMMENT '最后修改管理员用户 ID',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    CONSTRAINT chk_auto_approval_risk CHECK (max_risk_level IN ('low', 'medium'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 命令域自动审批策略（单行）';

INSERT INTO ai_command_auto_approval_policies (id, enabled, max_risk_level)
VALUES (1, 0, 'medium');

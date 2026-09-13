-- V35：Agent Token 轮换宽限期（2026-09-14 安全评审"加固"项：轮换无宽限期致瞬断需人工介入）
-- 1) servers 增列：轮换时旧 Token 摘要移入 agent_token_hash_prev 并登记宽限截止时间，
--    旧 Token 在宽限窗口内仍可完成握手，避免轮换瞬间在线 Agent 被拒需人工重新注册。
ALTER TABLE `servers`
    ADD COLUMN `agent_token_hash_prev` VARCHAR(64) NULL COMMENT '轮换宽限期内旧 Token SHA-256 摘要（sha256: 前缀十六进制）' AFTER `agent_token_hash`,
    ADD COLUMN `agent_token_grace_until` DATETIME NULL COMMENT '旧 Token 宽限期截止时间（UTC，超过后旧摘要失效）' AFTER `agent_token_hash_prev`;

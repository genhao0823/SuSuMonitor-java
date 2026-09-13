-- V36：修复 V35 列宽缺陷（2026-09-14 联调发现，见 Bug-fix/2026-09-14-agent-token-rotate-500-and-grace-column.md）
-- 1) agent_token_hash_prev 定义为 VARCHAR(64)，但 Token 摘要为 "sha256:" 前缀 + 64 位 hex 共 71 字符，
--    轮换写入触发 Data too long → 轮换接口 500/50001。既有列 agent_token_hash 为 varchar(255)，
--    本迁移对齐列宽。V35 已入共享库，按规范不改只追加。
ALTER TABLE `servers`
    MODIFY COLUMN `agent_token_hash_prev` VARCHAR(255) NULL COMMENT '轮换宽限期内旧 Token SHA-256 摘要（sha256: 前缀十六进制）';

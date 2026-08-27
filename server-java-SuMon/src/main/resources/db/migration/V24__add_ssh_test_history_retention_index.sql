-- V24: SSH 测试历史保留期清理索引
-- ssh_test_history 会随每次 SSH 连接测试持续增长，需按 tested_at 范围分批清理。
-- V23 的 (server_id, tested_at) 复合索引不满足 tested_at 单独范围扫描，
-- 此处补充单列索引支撑保留期清理的 DELETE 定位。
ALTER TABLE ssh_test_history
    ADD INDEX idx_ssh_test_history_tested_at (tested_at);

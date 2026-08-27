-- 告警逃逸窗口（MVP 加固）：规则级确认次数 + 状态级连续越界计数。
-- confirm_count 默认 1 = 首次越界立即触发（与改造前行为一致，向后兼容）；
-- 设为 N>1 则连续越界 N 次触发，防瞬时抖动误报。
ALTER TABLE `alert_rules`
    ADD COLUMN `confirm_count` INT NOT NULL DEFAULT 1 COMMENT '连续越界确认次数: 1=立即触发, >1=连续N次越界触发' AFTER `level`;

ALTER TABLE `alert_states`
    ADD COLUMN `breach_count` INT NOT NULL DEFAULT 0 COMMENT '当前连续越界计数（未达 confirm_count 时的累计值）' AFTER `active`;

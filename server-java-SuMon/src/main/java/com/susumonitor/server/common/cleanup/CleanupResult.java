package com.susumonitor.server.common.cleanup;

import java.time.LocalDateTime;

/**
 * 一轮批量清理的统计结果，由 {@link BatchCleanupExecutor} 产出。
 *
 * @param cutoffTime 过期边界，严格早于该时间才删除
 * @param batchCount 本轮执行的批次数
 * @param deletedRows 本轮删除的总行数
 * @param durationMs 本轮耗时（毫秒）
 */
public record CleanupResult(LocalDateTime cutoffTime, int batchCount, int deletedRows, long durationMs) {
}

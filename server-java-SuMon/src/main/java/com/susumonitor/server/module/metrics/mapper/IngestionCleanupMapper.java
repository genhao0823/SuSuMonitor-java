package com.susumonitor.server.module.metrics.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 执行指标幂等接收记录（metrics_ingestions）的分批删除，避免清理任务读取或加载明细。
 */
@Mapper
public interface IngestionCleanupMapper {

    /**
     * 按接收时间和主键顺序删除一批过期接收记录。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @param batchSize 本批最大删除数量
     * @return 实际删除行数
     */
    int deleteExpiredBatch(
            @Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("batchSize") int batchSize);
}

package com.susumonitor.server.module.alert.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 执行告警记录（alert_records）的分批删除，避免清理任务读取或加载告警明细。
 */
@Mapper
public interface AlertRecordCleanupMapper {

    /**
     * 按触发时间和主键顺序删除一批过期告警记录。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @param batchSize 本批最大删除数量
     * @return 实际删除行数
     */
    int deleteExpiredBatch(
            @Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("batchSize") int batchSize);
}

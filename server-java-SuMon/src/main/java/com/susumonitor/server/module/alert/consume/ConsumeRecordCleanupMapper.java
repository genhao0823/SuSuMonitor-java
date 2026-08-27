package com.susumonitor.server.module.alert.consume;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 执行消费幂等记录（message_consume_records）的分批删除。
 *
 * <p>不区分 consumed/failed 状态统一按接收时间清理：failed 行仅作失败留痕，
 * {@code existsConsumed} 只认 consumed，删除后不影响幂等语义。</p>
 */
@Mapper
public interface ConsumeRecordCleanupMapper {

    /**
     * 按接收时间和主键顺序删除一批过期消费记录。
     *
     * @param cutoffTime 过期边界，严格早于该时间才删除
     * @param batchSize 本批最大删除数量
     * @return 实际删除行数
     */
    int deleteExpiredBatch(
            @Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("batchSize") int batchSize);
}

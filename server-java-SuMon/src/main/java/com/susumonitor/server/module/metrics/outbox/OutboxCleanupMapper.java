package com.susumonitor.server.module.metrics.outbox;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

/**
 * 分批删除已确认发布且超过保留期的 Outbox 记录。
 */
@Mapper
@ConditionalOnBean(SqlSessionFactory.class)
public interface OutboxCleanupMapper {

    /**
     * 删除一批严格早于边界的已发布记录；pending 重试事件不会被选中。
     *
     * @param cutoffTime 已发布记录过期边界
     * @param batchSize 本批最大删除数量
     * @return 实际删除行数
     */
    int deletePublishedBeforeBatch(
            @Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("batchSize") int batchSize);
}

package com.susumonitor.server.module.ai.mapper;

import com.susumonitor.server.module.ai.entity.AiQaRunEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 访问 AI 运维问答最小审计记录。 */
@Mapper
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public interface AiQaRunMapper {
    int insertRun(@Param("run") AiQaRunEntity run);
    int completeRun(@Param("run") AiQaRunEntity run);
    int failRun(@Param("run") AiQaRunEntity run);
    int deleteExpiredBatch(@Param("cutoffTime") LocalDateTime cutoffTime, @Param("batchSize") int batchSize);

    /**
     * 统计时间窗口内 completed 问答的 token 总量，供按天预算校验使用。
     *
     * @param startTime 窗口起点（含）
     * @param endTime 窗口终点（不含）
     * @return token 总量；无记录时返回 0
     */
    Long selectTotalTokensBetween(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}

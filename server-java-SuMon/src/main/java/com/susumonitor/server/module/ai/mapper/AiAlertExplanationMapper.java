package com.susumonitor.server.module.ai.mapper;

import com.susumonitor.server.module.ai.entity.AiAlertExplanationEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 访问 AI 告警智能解释存储。
 *
 * <p>随 {@code susumonitor.ai.explanation.enabled} 开关装配：关闭时不创建代理，
 * 依赖本 Mapper 的消费者/端点必须使用相同的条件装配，避免启动失败。</p>
 */
@Mapper
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public interface AiAlertExplanationMapper {
    int insert(@Param("explanation") AiAlertExplanationEntity explanation);

    AiAlertExplanationEntity selectByRecordId(@Param("alertRecordId") Long alertRecordId);

    /**
     * 统计时间窗口内已落库解释的 token 总量，供按天预算校验使用。
     *
     * @param startTime 窗口起点（含）
     * @param endTime 窗口终点（不含）
     * @return token 总量；无记录时返回 0
     */
    Long sumTotalTokensBetween(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    int deleteExpiredBatch(@Param("cutoffTime") LocalDateTime cutoffTime, @Param("batchSize") int batchSize);
}

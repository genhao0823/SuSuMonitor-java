package com.susumonitor.server.module.ai.mapper;

import com.susumonitor.server.module.ai.entity.AiDiagnosticRunEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 访问 AI 诊断最小审计记录。 */
@Mapper
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public interface AiDiagnosticRunMapper {
    int insertRun(@Param("run") AiDiagnosticRunEntity run);
    int completeRun(@Param("run") AiDiagnosticRunEntity run);
    int failRun(@Param("run") AiDiagnosticRunEntity run);
    int deleteExpiredBatch(@Param("cutoffTime") LocalDateTime cutoffTime, @Param("batchSize") int batchSize);
}

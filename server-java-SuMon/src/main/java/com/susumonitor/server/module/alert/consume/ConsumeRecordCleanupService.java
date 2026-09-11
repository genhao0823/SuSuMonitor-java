package com.susumonitor.server.module.alert.consume;

import com.susumonitor.server.common.cleanup.CleanupResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 定义消费幂等记录保留期清理的调度业务契约。
 */
public interface ConsumeRecordCleanupService {

    /** 按配置的保留周期清理过期消费记录。 */
    Optional<CleanupResult> cleanupExpiredConsumeRecords();

    /** 按指定时间边界清理过期消费记录，供验证场景固定边界。 */
    Optional<CleanupResult> cleanupExpiredConsumeRecords(LocalDateTime cutoffTime);
}

package com.susumonitor.server.module.alert.mapper;

import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 告警通知投递记录 Mapper，支撑退避重试。
 */
@Mapper
public interface AlertNotificationMapper {

    /** 插入待发送通知，回写主键。 */
    int insert(@Param("entity") AlertNotificationEntity entity);

    /** 查询到期待重试的 pending 通知（next_attempt_at 为空或已到期），按到期时间升序。 */
    List<AlertNotificationEntity> selectPendingForRetry(@Param("now") LocalDateTime now,
            @Param("limit") int limit);

    /** 记录一次发送结果：状态、尝试次数、下次重试时间与失败原因。 */
    int markAttempt(@Param("id") Long id, @Param("status") String status,
            @Param("attempts") Integer attempts, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("lastError") String lastError);
}

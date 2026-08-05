package com.susumonitor.server.module.alert.mapper;

import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 告警记录 Mapper，提供记录插入、状态更新和分页查询。
 */
@Mapper
public interface AlertRecordMapper {

    /** 插入告警记录，回写主键。 */
    int insertRecord(@Param("record") AlertRecordEntity record);

    /** 根据主键查询单条记录。 */
    AlertRecordEntity selectRecordById(@Param("id") Long id);

    /** 更新记录状态为已读。 */
    int updateStatusToRead(@Param("id") Long id, @Param("readBy") Long readBy,
            @Param("readAt") LocalDateTime readAt);

    /** 更新记录状态为已恢复。 */
    int updateStatusToResolved(@Param("id") Long id, @Param("resolvedAt") LocalDateTime resolvedAt);

    /** 记录外部通知发送完成时间与已发送渠道；未发送过通知时首次写入。 */
    int updateNotifiedInfo(@Param("id") Long id, @Param("notifiedAt") LocalDateTime notifiedAt,
            @Param("notifyChannels") String notifyChannels);

    /** 分页查询告警记录，支持按服务器和状态筛选。 */
    List<AlertRecordEntity> selectRecords(@Param("serverId") Long serverId,
            @Param("status") String status, @Param("offset") long offset, @Param("pageSize") int pageSize);

    /** 统计告警记录总数，支持按服务器和状态筛选。 */
    long countRecords(@Param("serverId") Long serverId, @Param("status") String status);
}

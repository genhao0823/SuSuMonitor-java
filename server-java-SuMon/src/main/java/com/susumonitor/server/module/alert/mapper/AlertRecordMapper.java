package com.susumonitor.server.module.alert.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
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

    /**
     * 分页查询告警记录，支持按服务器和状态筛选。
     *
     * <p>分页由 MyBatis-Plus 分页拦截器承担：携带 IPage 参数自动执行 COUNT
     * 并追加 LIMIT，total 回写到传入的 Page 对象。</p>
     */
    List<AlertRecordEntity> selectRecords(IPage<AlertRecordEntity> page, @Param("serverId") Long serverId,
            @Param("status") String status);
}

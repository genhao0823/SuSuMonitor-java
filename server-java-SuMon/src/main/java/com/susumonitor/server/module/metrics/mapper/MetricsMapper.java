package com.susumonitor.server.module.metrics.mapper;

import com.susumonitor.server.module.metrics.entity.MetricsEntity;
import com.susumonitor.server.module.metrics.entity.MetricsIngestionEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 访问 Metrics 固定宽表，负责写入和 latest/history 查询。 */
@Mapper
public interface MetricsMapper {
    /**
     * 插入一条指标记录。
     *
     * @param metric 指标实体
     * @return 影响行数
     */
    int insertMetric(@Param("metric") MetricsEntity metric);
    /**
     * 插入一条消息幂等记录。
     *
     * @param ingestion 消息幂等实体
     * @return 影响行数
     */
    int insertIngestion(@Param("ingestion") MetricsIngestionEntity ingestion);
    /**
     * 查询服务器最近一次采集时间。
     *
     * @param serverId 服务器 ID
     * @return 最近采集时间
     */
    LocalDateTime selectLatestCollectedAt(@Param("serverId") Long serverId);
    /**
     * 查询服务器最新一条指标。
     *
     * @param serverId 服务器 ID
     * @return 最新指标实体
     */
    MetricsEntity selectLatestByServerId(@Param("serverId") Long serverId);
    /**
     * 分页查询服务器历史指标。
     *
     * @param serverId 服务器 ID
     * @param startTime 起始时间
     * @param endTime 结束时间
     * @param offset 偏移量
     * @param pageSize 每页条数
     * @return 历史指标列表
     */
    List<MetricsEntity> selectHistory(@Param("serverId") Long serverId,
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime,
            @Param("offset") long offset, @Param("pageSize") int pageSize);
    /**
     * 统计服务器历史指标总数。
     *
     * @param serverId 服务器 ID
     * @param startTime 起始时间
     * @param endTime 结束时间
     * @return 历史指标总数
     */
    long countHistory(@Param("serverId") Long serverId,
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}

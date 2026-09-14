package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.metrics.dto.MetricsReportPayload;
import com.susumonitor.server.module.metrics.vo.MetricsHistoryVo;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import java.time.OffsetDateTime;

/**
 * 定义 Agent 指标写入和查询的业务契约，供 HTTP 与 WebSocket 适配层依赖。
 */
public interface MetricsService {

    /** 写入已认证 Agent 的指标快照。 */
    void report(Long authenticatedServerId, String messageId, MetricsReportPayload payload);

    /** 查询服务器最新指标。 */
    MetricsLatestVo latest(Long serverId);

    /** 分页查询服务器指标历史。 */
    PageResult<MetricsHistoryVo> history(Long serverId, OffsetDateTime startTime,
            OffsetDateTime endTime, Integer page, Integer pageSize);

    /**
     * 表示已写入数据库、等待事务提交后广播的指标事件。
     *
     * @param metrics 最新指标快照
     */
    record MetricsReportedEvent(MetricsLatestVo metrics) {
    }
}

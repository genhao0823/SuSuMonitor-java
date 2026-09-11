package com.susumonitor.server.module.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 定时健康报告的白名单聚合事实（F3），由服务端只读 SQL 聚合而来，
 * 供报告 prompt 使用；不含服务器地址、SSH、凭据、通知渠道或任何自由文本。
 */
public record AiHealthReportFacts(
        @JsonProperty("report_date") String reportDate,
        @JsonProperty("server_inventory") ServerInventory serverInventory,
        @JsonProperty("metric_peaks") MetricPeaks metricPeaks,
        @JsonProperty("alert_statistics") AlertStatistics alertStatistics,
        @JsonProperty("offline_servers") List<OfflineServer> offlineServers) {

    /** 服务器清单快照：总数 / 在线数 / 离线数与在线率（百分比，一位小数）。 */
    public record ServerInventory(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("online_count") int onlineCount,
            @JsonProperty("offline_count") int offlineCount,
            @JsonProperty("online_rate") double onlineRate) {
    }

    /** 报告窗口内的指标聚合：全量服务器平均值与峰值（峰值附带服务器 ID 供定位）。 */
    public record MetricPeaks(
            @JsonProperty("avg_cpu_percent") Double avgCpuPercent,
            @JsonProperty("max_cpu_percent") Double maxCpuPercent,
            @JsonProperty("max_cpu_server_id") Long maxCpuServerId,
            @JsonProperty("avg_memory_percent") Double avgMemoryPercent,
            @JsonProperty("max_memory_percent") Double maxMemoryPercent,
            @JsonProperty("max_memory_server_id") Long maxMemoryServerId,
            @JsonProperty("avg_disk_percent") Double avgDiskPercent,
            @JsonProperty("max_disk_percent") Double maxDiskPercent,
            @JsonProperty("max_disk_server_id") Long maxDiskServerId) {
    }

    /** 报告窗口内的告警统计：触发/恢复计数、分级计数与告警最多的服务器。 */
    public record AlertStatistics(
            @JsonProperty("total_triggered") int totalTriggered,
            @JsonProperty("critical_count") int criticalCount,
            @JsonProperty("warning_count") int warningCount,
            @JsonProperty("resolved_count") int resolvedCount,
            @JsonProperty("unresolved_count") int unresolvedCount,
            @JsonProperty("top_servers") List<TopAlertServer> topServers) {
    }

    /** 告警最多的服务器（白名单：仅 ID、名称与计数）。 */
    public record TopAlertServer(
            @JsonProperty("server_id") Long serverId,
            @JsonProperty("server_name") String serverName,
            @JsonProperty("alert_count") int alertCount,
            @JsonProperty("critical_count") int criticalCount) {
    }

    /** 当前离线服务器快照（白名单：仅 ID、名称、Agent 状态与最后心跳时间）。 */
    public record OfflineServer(
            @JsonProperty("server_id") Long serverId,
            @JsonProperty("server_name") String serverName,
            @JsonProperty("agent_status") String agentStatus,
            @JsonProperty("last_heartbeat_at") String lastHeartbeatAt) {
    }
}

package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** 对外返回单个服务器最新固定宽表指标。 */
// 类级 @Schema 描述固定宽表指标模型，供 springdoc 生成响应模型说明。
@Data
@Schema(description = "固定宽表指标（最新值或历史行）")
public class MetricsLatestVo {
    @JsonProperty("server_id") @Schema(description = "服务器 ID", minimum = "1") private Long serverId;
    @JsonProperty("cpu_percent") @Schema(description = "CPU 使用率（0-100）", minimum = "0", maximum = "100")
    private BigDecimal cpuPercent;
    @JsonProperty("memory_percent") @Schema(description = "内存使用率（0-100）", minimum = "0", maximum = "100")
    private BigDecimal memoryPercent;
    @JsonProperty("memory_used") @Schema(description = "已用内存字节数", minimum = "0") private Long memoryUsed;
    @JsonProperty("memory_total") @Schema(description = "内存总字节数", minimum = "0") private Long memoryTotal;
    @JsonProperty("disk_percent") @Schema(description = "磁盘使用率（0-100）", minimum = "0", maximum = "100")
    private BigDecimal diskPercent;
    @JsonProperty("disk_used") @Schema(description = "已用磁盘字节数", minimum = "0") private Long diskUsed;
    @JsonProperty("disk_total") @Schema(description = "磁盘总字节数", minimum = "0") private Long diskTotal;
    @JsonProperty("net_rx") @Schema(description = "累计接收字节数", minimum = "0") private Long netRx;
    @JsonProperty("net_tx") @Schema(description = "累计发送字节数", minimum = "0") private Long netTx;
    @Schema(description = "温度（采集平台不支持为 null）") private BigDecimal temperature;
    // 描述负载均值字段，注明不是百分比，采集平台不支持时为 null。
    @JsonProperty("load_avg")
    @Schema(description = "系统负载均值（非百分比）；采集平台不提供时为 null", minimum = "0")
    private BigDecimal loadAvg;
    @JsonProperty("collected_at") @Schema(description = "采集时间（UTC ISO-8601）") private OffsetDateTime collectedAt;
}

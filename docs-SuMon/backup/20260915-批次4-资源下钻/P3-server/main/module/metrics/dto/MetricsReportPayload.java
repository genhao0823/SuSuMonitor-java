package com.susumonitor.server.module.metrics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 单次指标上报载荷，与 Metrics 固定宽表字段一一对应。
 *
 * <p>进程排行字段为 websocket-protocol.md v1.4 新增的可选字段，
 * 旧版 Agent 整体省略；服务端只保留内存快照，不写库。</p>
 */
public class MetricsReportPayload {

    @JsonProperty("server_id")
    private Long serverId;
    @JsonProperty("collected_at")
    private OffsetDateTime collectedAt;
    @JsonProperty("cpu_percent")
    private BigDecimal cpuPercent;
    @JsonProperty("memory_percent")
    private BigDecimal memoryPercent;
    @JsonProperty("memory_used")
    private Long memoryUsed;
    @JsonProperty("memory_total")
    private Long memoryTotal;
    @JsonProperty("disk_percent")
    private BigDecimal diskPercent;
    @JsonProperty("disk_used")
    private Long diskUsed;
    @JsonProperty("disk_total")
    private Long diskTotal;
    @JsonProperty("net_rx")
    private Long netRx;
    @JsonProperty("net_tx")
    private Long netTx;
    private BigDecimal temperature;
    @JsonProperty("load_avg")
    private BigDecimal loadAvg;
    @JsonProperty("process_cpu_top")
    private List<ProcessSamplePayload> processCpuTop;
    @JsonProperty("process_mem_top")
    private List<ProcessSamplePayload> processMemTop;

    /** 获取服务器 ID。 */
    public Long getServerId() { return serverId; }
    /** 设置服务器 ID。 */
    public void setServerId(Long serverId) { this.serverId = serverId; }
    /** 获取采集时间。 */
    public OffsetDateTime getCollectedAt() { return collectedAt; }
    /** 设置采集时间。 */
    public void setCollectedAt(OffsetDateTime collectedAt) { this.collectedAt = collectedAt; }
    /** 获取 CPU 使用率百分比。 */
    public BigDecimal getCpuPercent() { return cpuPercent; }
    /** 设置 CPU 使用率百分比。 */
    public void setCpuPercent(BigDecimal cpuPercent) { this.cpuPercent = cpuPercent; }
    /** 获取内存使用率百分比。 */
    public BigDecimal getMemoryPercent() { return memoryPercent; }
    /** 设置内存使用率百分比。 */
    public void setMemoryPercent(BigDecimal memoryPercent) { this.memoryPercent = memoryPercent; }
    /** 获取已使用内存（字节）。 */
    public Long getMemoryUsed() { return memoryUsed; }
    /** 设置已使用内存（字节）。 */
    public void setMemoryUsed(Long memoryUsed) { this.memoryUsed = memoryUsed; }
    /** 获取总内存（字节）。 */
    public Long getMemoryTotal() { return memoryTotal; }
    /** 设置总内存（字节）。 */
    public void setMemoryTotal(Long memoryTotal) { this.memoryTotal = memoryTotal; }
    /** 获取磁盘使用率百分比。 */
    public BigDecimal getDiskPercent() { return diskPercent; }
    /** 设置磁盘使用率百分比。 */
    public void setDiskPercent(BigDecimal diskPercent) { this.diskPercent = diskPercent; }
    /** 获取已使用磁盘空间（字节）。 */
    public Long getDiskUsed() { return diskUsed; }
    /** 设置已使用磁盘空间（字节）。 */
    public void setDiskUsed(Long diskUsed) { this.diskUsed = diskUsed; }
    /** 获取总磁盘空间（字节）。 */
    public Long getDiskTotal() { return diskTotal; }
    /** 设置总磁盘空间（字节）。 */
    public void setDiskTotal(Long diskTotal) { this.diskTotal = diskTotal; }
    /** 获取网络接收字节数。 */
    public Long getNetRx() { return netRx; }
    /** 设置网络接收字节数。 */
    public void setNetRx(Long netRx) { this.netRx = netRx; }
    /** 获取网络发送字节数。 */
    public Long getNetTx() { return netTx; }
    /** 设置网络发送字节数。 */
    public void setNetTx(Long netTx) { this.netTx = netTx; }
    /** 获取温度。 */
    public BigDecimal getTemperature() { return temperature; }
    /** 设置温度。 */
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    /** 获取系统负载均值。 */
    public BigDecimal getLoadAvg() { return loadAvg; }
    /** 设置系统负载均值。 */
    public void setLoadAvg(BigDecimal loadAvg) { this.loadAvg = loadAvg; }
    /** 获取按 CPU 占比降序的 Top 进程列表；旧版 Agent 为 null。 */
    public List<ProcessSamplePayload> getProcessCpuTop() { return processCpuTop; }
    /** 设置按 CPU 占比降序的 Top 进程列表。 */
    public void setProcessCpuTop(List<ProcessSamplePayload> processCpuTop) { this.processCpuTop = processCpuTop; }
    /** 获取按内存占比降序的 Top 进程列表；旧版 Agent 为 null。 */
    public List<ProcessSamplePayload> getProcessMemTop() { return processMemTop; }
    /** 设置按内存占比降序的 Top 进程列表。 */
    public void setProcessMemTop(List<ProcessSamplePayload> processMemTop) { this.processMemTop = processMemTop; }
}

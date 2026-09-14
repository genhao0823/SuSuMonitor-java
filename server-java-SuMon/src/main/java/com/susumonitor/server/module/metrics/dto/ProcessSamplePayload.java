package com.susumonitor.server.module.metrics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Agent 上报的单个 Top 进程条目（websocket-protocol.md v1.4）。
 *
 * <p>只携带 pid、进程名与占用比例；命令行与环境变量禁止进入协议字段。</p>
 */
public record ProcessSamplePayload(
        @JsonProperty("pid") Integer pid,
        @JsonProperty("name") String name,
        @JsonProperty("cpu_percent") BigDecimal cpuPercent,
        @JsonProperty("mem_percent") BigDecimal memPercent) {
}

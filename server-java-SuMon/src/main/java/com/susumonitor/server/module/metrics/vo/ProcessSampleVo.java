package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 单个 Top 进程条目视图，与协议字段一一对应。
 */
public record ProcessSampleVo(
        // 描述进程 ID，协议要求正整数。
        @JsonProperty("pid") @Schema(description = "进程 ID", minimum = "1") Integer pid,
        // 描述进程名；只含 OS 进程名，不含命令行与凭据。
        @JsonProperty("name") @Schema(description = "进程名（不含命令行）", minLength = 1, maxLength = 128) String name,
        // 描述 CPU 占比；按整机核数归一化后的 0-100 值。
        @JsonProperty("cpu_percent") @Schema(description = "CPU 占比（0-100，按整机核数归一化）",
                minimum = "0", maximum = "100") BigDecimal cpuPercent,
        // 描述内存占比；进程物理内存占整机内存的 0-100 值。
        @JsonProperty("mem_percent") @Schema(description = "内存占比（0-100）",
                minimum = "0", maximum = "100") BigDecimal memPercent) {
}

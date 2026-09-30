package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 服务器实时 Top 进程快照视图（内存保留、不落库，90 秒新鲜窗口）。
 */
public record ProcessSnapshotVo(
        @JsonProperty("server_id") @Schema(description = "服务器 ID", minimum = "1") Long serverId,
        @JsonProperty("collected_at") @Schema(description = "采集时间（UTC ISO-8601）") OffsetDateTime collectedAt,
        @JsonProperty("cpu_top") @Schema(description = "按 CPU 占比降序的 Top 进程") List<ProcessSampleVo> cpuTop,
        @JsonProperty("mem_top") @Schema(description = "按内存占比降序的 Top 进程") List<ProcessSampleVo> memTop) {
}

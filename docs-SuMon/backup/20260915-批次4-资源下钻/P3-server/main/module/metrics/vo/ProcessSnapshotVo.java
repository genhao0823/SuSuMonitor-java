package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 服务器实时 Top 进程快照视图（内存保留、不落库，90 秒新鲜窗口）。
 */
public record ProcessSnapshotVo(
        // 描述服务器 ID。
        @JsonProperty("server_id") @Schema(description = "服务器 ID", minimum = "1") Long serverId,
        // 描述快照采集时间；与触发上报的指标行 collected_at 一致（UTC ISO-8601）。
        @JsonProperty("collected_at") @Schema(description = "采集时间（UTC ISO-8601）") OffsetDateTime collectedAt,
        // 描述按 CPU 占比降序的 Top 进程列表。
        @JsonProperty("cpu_top") @Schema(description = "按 CPU 占比降序的 Top 进程") List<ProcessSampleVo> cpuTop,
        // 描述按内存占比降序的 Top 进程列表。
        @JsonProperty("mem_top") @Schema(description = "按内存占比降序的 Top 进程") List<ProcessSampleVo> memTop) {
}

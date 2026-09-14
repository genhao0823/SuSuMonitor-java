package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 服务器实时磁盘/网卡扩展资源快照视图（内存保留、不落库，90 秒新鲜窗口）。
 */
public record ServerResourcesSnapshotVo(
        // 描述服务器 ID。
        @JsonProperty("server_id") @Schema(description = "服务器 ID", minimum = "1") Long serverId,
        // 描述快照采集时间；与触发上报的指标行 collected_at 一致（UTC ISO-8601）。
        @JsonProperty("collected_at") @Schema(description = "采集时间（UTC ISO-8601）") OffsetDateTime collectedAt,
        // 描述按总容量降序的每挂载点容量列表（协议上限 32 条，见 openapi-server.json）。
        @JsonProperty("disks") @Schema(description = "按总容量降序的每挂载点容量") List<DiskSampleVo> disks,
        // 描述按接收速率降序的分网卡吞吐列表（协议上限 64 条，见 openapi-server.json）。
        @JsonProperty("nics") @Schema(description = "按接收速率降序的分网卡吞吐") List<NicSampleVo> nics) {
}

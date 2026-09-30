package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 服务器实时磁盘/网卡扩展资源快照视图（内存保留、不落库，90 秒新鲜窗口）。
 */
public record ServerResourcesSnapshotVo(
        @JsonProperty("server_id") @Schema(description = "服务器 ID", minimum = "1") Long serverId,
        @JsonProperty("collected_at") @Schema(description = "采集时间（UTC ISO-8601）") OffsetDateTime collectedAt,
        @JsonProperty("disks") @Schema(description = "按总容量降序的每挂载点容量") List<DiskSampleVo> disks,
        @JsonProperty("nics") @Schema(description = "按接收速率降序的分网卡吞吐") List<NicSampleVo> nics) {
}

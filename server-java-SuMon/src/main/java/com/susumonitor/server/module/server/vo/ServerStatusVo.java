package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回服务器和 Agent 的数据库状态快照及本次查询时间。
 */
@Data
@Schema(description = "服务器与 Agent 的数据库状态快照；读取不触发实时探测")
public class ServerStatusVo {

    @JsonProperty("server_id")
    @Schema(description = "服务器 ID", minimum = "1")
    private Long serverId;
    @Schema(description = "服务器状态（数据库快照）")
    private String status;
    @JsonProperty("agent_status")
    @Schema(description = "Agent 状态")
    private String agentStatus;
    @JsonProperty("last_heartbeat_at")
    @Schema(description = "最近心跳时间（未收到心跳为 null）")
    private OffsetDateTime lastHeartbeatAt;
    @JsonProperty("delivery_pending_count")
    @Schema(description = "待投递消息数")
    private Long deliveryPendingCount;
    @JsonProperty("delivery_pending_bytes")
    @Schema(description = "待投递消息字节数")
    private Long deliveryPendingBytes;
    @JsonProperty("delivery_oldest_collected_at")
    @Schema(description = "最旧待投递消息的采集时间")
    private OffsetDateTime deliveryOldestCollectedAt;
    @JsonProperty("delivery_drop_count")
    @Schema(description = "已丢弃消息数")
    private Long deliveryDropCount;
    @JsonProperty("delivery_dead_letter_count")
    @Schema(description = "死信消息数")
    private Long deliveryDeadLetterCount;
    @JsonProperty("delivery_dead_letter_bytes")
    @Schema(description = "死信消息字节数")
    private Long deliveryDeadLetterBytes;
    @JsonProperty("checked_at")
    @Schema(description = "本次快照查询时间（UTC ISO-8601）")
    private OffsetDateTime checkedAt;

}

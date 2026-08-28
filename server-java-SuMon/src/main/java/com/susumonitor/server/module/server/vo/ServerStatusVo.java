package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回服务器和 Agent 的数据库状态快照及本次查询时间。
 */
// 自动生成当前 VO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
// 类级 @Schema 描述状态快照模型，读取该接口不触发实时探测，供 springdoc 生成响应模型。
@Schema(description = "服务器与 Agent 的数据库状态快照；读取不触发实时探测")
public class ServerStatusVo {

    // 将 Java 的 serverId 属性映射为接口 JSON 字段 server_id。
    @JsonProperty("server_id")
    @Schema(description = "服务器 ID", minimum = "1")
    private Long serverId;
    @Schema(description = "服务器状态（数据库快照）")
    private String status;
    // 将 Java 的 agentStatus 属性映射为接口 JSON 字段 agent_status。
    @JsonProperty("agent_status")
    @Schema(description = "Agent 状态")
    private String agentStatus;
    // 将 Java 的 lastHeartbeatAt 属性映射为接口 JSON 字段 last_heartbeat_at。
    @JsonProperty("last_heartbeat_at")
    @Schema(description = "最近心跳时间（未收到心跳为 null）")
    private OffsetDateTime lastHeartbeatAt;
    // 将 Java 的 deliveryPendingCount 属性映射为接口 JSON 字段 delivery_pending_count。
    @JsonProperty("delivery_pending_count")
    @Schema(description = "待投递消息数")
    private Long deliveryPendingCount;
    // 将 Java 的 deliveryPendingBytes 属性映射为接口 JSON 字段 delivery_pending_bytes。
    @JsonProperty("delivery_pending_bytes")
    @Schema(description = "待投递消息字节数")
    private Long deliveryPendingBytes;
    // 将 Java 的 deliveryOldestCollectedAt 属性映射为接口 JSON 字段 delivery_oldest_collected_at。
    @JsonProperty("delivery_oldest_collected_at")
    @Schema(description = "最旧待投递消息的采集时间")
    private OffsetDateTime deliveryOldestCollectedAt;
    // 将 Java 的 deliveryDropCount 属性映射为接口 JSON 字段 delivery_drop_count。
    @JsonProperty("delivery_drop_count")
    @Schema(description = "已丢弃消息数")
    private Long deliveryDropCount;
    // 将 Java 的 deliveryDeadLetterCount 属性映射为接口 JSON 字段 delivery_dead_letter_count。
    @JsonProperty("delivery_dead_letter_count")
    @Schema(description = "死信消息数")
    private Long deliveryDeadLetterCount;
    // 将 Java 的 deliveryDeadLetterBytes 属性映射为接口 JSON 字段 delivery_dead_letter_bytes。
    @JsonProperty("delivery_dead_letter_bytes")
    @Schema(description = "死信消息字节数")
    private Long deliveryDeadLetterBytes;
    // 将 Java 的 checkedAt 属性映射为接口 JSON 字段 checked_at。
    @JsonProperty("checked_at")
    @Schema(description = "本次快照查询时间（UTC ISO-8601）")
    private OffsetDateTime checkedAt;

}

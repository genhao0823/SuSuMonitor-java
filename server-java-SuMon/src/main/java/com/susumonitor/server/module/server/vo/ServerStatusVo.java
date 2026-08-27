package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回服务器和 Agent 的数据库状态快照及本次查询时间。
 */
// 自动生成当前 VO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
public class ServerStatusVo {

    // 将 Java 的 serverId 属性映射为接口 JSON 字段 server_id。
    @JsonProperty("server_id")
    private Long serverId;
    private String status;
    // 将 Java 的 agentStatus 属性映射为接口 JSON 字段 agent_status。
    @JsonProperty("agent_status")
    private String agentStatus;
    // 将 Java 的 lastHeartbeatAt 属性映射为接口 JSON 字段 last_heartbeat_at。
    @JsonProperty("last_heartbeat_at")
    private OffsetDateTime lastHeartbeatAt;
    // 将 Java 的 deliveryPendingCount 属性映射为接口 JSON 字段 delivery_pending_count。
    @JsonProperty("delivery_pending_count")
    private Long deliveryPendingCount;
    // 将 Java 的 deliveryPendingBytes 属性映射为接口 JSON 字段 delivery_pending_bytes。
    @JsonProperty("delivery_pending_bytes")
    private Long deliveryPendingBytes;
    // 将 Java 的 deliveryOldestCollectedAt 属性映射为接口 JSON 字段 delivery_oldest_collected_at。
    @JsonProperty("delivery_oldest_collected_at")
    private OffsetDateTime deliveryOldestCollectedAt;
    // 将 Java 的 deliveryDropCount 属性映射为接口 JSON 字段 delivery_drop_count。
    @JsonProperty("delivery_drop_count")
    private Long deliveryDropCount;
    // 将 Java 的 deliveryDeadLetterCount 属性映射为接口 JSON 字段 delivery_dead_letter_count。
    @JsonProperty("delivery_dead_letter_count")
    private Long deliveryDeadLetterCount;
    // 将 Java 的 deliveryDeadLetterBytes 属性映射为接口 JSON 字段 delivery_dead_letter_bytes。
    @JsonProperty("delivery_dead_letter_bytes")
    private Long deliveryDeadLetterBytes;
    // 将 Java 的 checkedAt 属性映射为接口 JSON 字段 checked_at。
    @JsonProperty("checked_at")
    private OffsetDateTime checkedAt;

}

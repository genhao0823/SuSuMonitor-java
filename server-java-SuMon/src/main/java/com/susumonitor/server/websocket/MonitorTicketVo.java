package com.susumonitor.server.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 返回一次性 Monitor WebSocket ticket。 */
@Schema(description = "Monitor WebSocket 一次性 ticket")
public record MonitorTicketVo(
        @JsonProperty("ticket")
        @Schema(description = "一次性 ticket，在 /ws/monitor 握手时消费", readOnly = true)
        String ticket,
        @JsonProperty("expires_at")
        @Schema(description = "过期时间（UTC ISO-8601），签发后 30 秒")
        OffsetDateTime expiresAt) {
}

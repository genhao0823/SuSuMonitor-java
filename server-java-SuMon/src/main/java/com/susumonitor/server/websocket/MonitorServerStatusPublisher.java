package com.susumonitor.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/** 向订阅指定服务器的 Monitor 会话广播 Agent 在线状态转换。 */
@Component
public class MonitorServerStatusPublisher {

    private final ObjectMapper objectMapper;
    private final MonitorSubscriptionRegistry registry;
    private final Clock clock;
    private final MonitorSessionTerminationService terminationService;

    /** 注入状态帧序列化、订阅查询和异常会话收口依赖。 */
    public MonitorServerStatusPublisher(ObjectMapper objectMapper, MonitorSubscriptionRegistry registry, Clock clock,
            MonitorSessionTerminationService terminationService) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.clock = clock;
        this.terminationService = terminationService;
    }

    /** 广播已成功持久化的在线或离线状态转换。 */
    public void publish(Long serverId, String status, String agentStatus, LocalDateTime lastHeartbeatAt) {
        for (MonitorWebSocketSession subscriber : registry.subscribers(serverId)) {
            try {
                if (!subscriber.send(new TextMessage(message(serverId, status, agentStatus, lastHeartbeatAt)))) {
                    terminationService.terminateNormally(subscriber);
                }
            } catch (MonitorBackpressureException exception) {
                terminationService.terminateForBackpressure(subscriber);
            } catch (IOException exception) {
                terminationService.terminateNormally(subscriber);
            }
        }
    }

    /** 构造与现有 Monitor 帧一致的状态事件。 */
    private String message(Long serverId, String status, String agentStatus, LocalDateTime lastHeartbeatAt)
            throws IOException {
        var payload = objectMapper.createObjectNode()
                .put("server_id", serverId)
                .put("status", status)
                .put("agent_status", agentStatus);
        if (lastHeartbeatAt == null) {
            payload.putNull("last_heartbeat_at");
        } else {
            payload.put("last_heartbeat_at", lastHeartbeatAt.atOffset(ZoneOffset.UTC).toString());
        }
        return objectMapper.createObjectNode()
                .put("type", "server.status.update")
                .put("message_id", UUID.randomUUID().toString())
                .put("timestamp", OffsetDateTime.now(clock).toString())
                .set("payload", payload)
                .toString();
    }
}

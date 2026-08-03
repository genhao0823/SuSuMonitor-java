package com.susumonitor.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.metrics.dto.MetricsReportPayload;
import com.susumonitor.server.module.metrics.service.MetricsService;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 处理 Agent 文本 WebSocket 首帧鉴权和心跳消息，不接收浏览器监控订阅。
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final Duration ERROR_CLOSE_DELAY = Duration.ofMillis(50);
    private final ObjectMapper objectMapper;
    private final AgentAuthenticationService authenticationService;
    private final AgentHeartbeatService heartbeatService;
    private final AgentConnectionRegistry connectionRegistry;
    private final MetricsService metricsService;
    private final Clock clock;
    private final AgentConnectionLimiter connectionLimiter;
    private final AgentMessageRateLimiter messageRateLimiter;
    private final TaskScheduler taskScheduler;
    private final TerminalAgentRelayService terminalRelayService;
    private final TerminalRelayLifecycleService terminalRelayLifecycleService;
    private final Map<String, AgentWebSocketSession> pendingSessions = new ConcurrentHashMap<>();

    /** 注入生产运行所需 JSON、鉴权、心跳、连接和资源限制依赖。 */
    @Autowired
    public AgentWebSocketHandler(
            ObjectMapper objectMapper,
            AgentAuthenticationService authenticationService,
            AgentHeartbeatService heartbeatService,
            AgentConnectionRegistry connectionRegistry,
            MetricsService metricsService,
            Clock clock,
            AgentConnectionLimiter connectionLimiter,
            AgentMessageRateLimiter messageRateLimiter,
            TaskScheduler taskScheduler, TerminalAgentRelayService terminalRelayService,
            TerminalRelayLifecycleService terminalRelayLifecycleService) {
        this.objectMapper = objectMapper;
        this.authenticationService = authenticationService;
        this.heartbeatService = heartbeatService;
        this.connectionRegistry = connectionRegistry;
        this.metricsService = metricsService;
        this.clock = clock;
        this.connectionLimiter = connectionLimiter;
        this.messageRateLimiter = messageRateLimiter;
        this.taskScheduler = taskScheduler;
        this.terminalRelayService = terminalRelayService;
        this.terminalRelayLifecycleService = terminalRelayLifecycleService;
    }

    /** 保留单元测试构造入口；生产 Spring 注入使用包含资源限制器的唯一构造器。 */
    AgentWebSocketHandler(ObjectMapper objectMapper, AgentAuthenticationService authenticationService,
            AgentHeartbeatService heartbeatService, AgentConnectionRegistry connectionRegistry,
            MetricsService metricsService, Clock clock) {
        this(objectMapper, authenticationService, heartbeatService, connectionRegistry, metricsService, clock,
                null, null, null, null, null);
    }

    /** 为现有限流测试保留的构造入口，终端响应中继由完整生产构造器注入。 */
    AgentWebSocketHandler(ObjectMapper objectMapper, AgentAuthenticationService authenticationService,
            AgentHeartbeatService heartbeatService, AgentConnectionRegistry connectionRegistry,
            MetricsService metricsService, Clock clock, AgentConnectionLimiter connectionLimiter,
            AgentMessageRateLimiter messageRateLimiter, TaskScheduler taskScheduler) {
        this(objectMapper, authenticationService, heartbeatService, connectionRegistry, metricsService, clock,
                connectionLimiter, messageRateLimiter, taskScheduler, null, null);
    }

    /** 保存新连接，等待其发送首帧认证。 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (connectionLimiter != null && !connectionLimiter.admit(session.getId())) {
            try {
                sendErrorAndClose(session, null, ErrorCode.AGENT_CONNECTION_LIMIT_REACHED);
            } catch (IOException exception) {
                close(session, CloseStatus.POLICY_VIOLATION);
            }
            return;
        }
        pendingSessions.put(session.getId(), new AgentWebSocketSession(session, clock));
    }

    /** 解析并分发 Agent 消息。 */
    @Override
    protected void handleTextMessage(WebSocketSession socketSession, TextMessage message) throws IOException {
        if (message.asBytes().length > MAX_MESSAGE_BYTES) {
            close(socketSession, new CloseStatus(1009, "message too big"));
            return;
        }
        AgentWebSocketSession session = pendingSessions.get(socketSession.getId());
        if (session == null) {
            close(socketSession, CloseStatus.SESSION_NOT_RELIABLE);
            return;
        }
        try {
            AgentMessage agentMessage = objectMapper.readValue(message.getPayload(), AgentMessage.class);
            if (AgentMessageType.AGENT_AUTHENTICATE.value().equals(agentMessage.type())) {
                authenticate(session, agentMessage.payload());
            } else if (AgentMessageType.HEARTBEAT.value().equals(agentMessage.type()) && session.authenticated()) {
                if (messageRateLimiter != null && !messageRateLimiter.allowHeartbeat(session.sessionId())) {
                    sendErrorAndClose(session.socketSession(), agentMessage.messageId(),
                            ErrorCode.AGENT_MESSAGE_RATE_LIMIT_REACHED);
                    return;
                }
                heartbeatService.heartbeat(session);
                // 冻结 heartbeat.ack payload，返回确认的心跳时间，供 Agent 校验心跳周期。
                var ackPayload = objectMapper.createObjectNode()
                        .put("server_id", session.serverId())
                        .put("last_heartbeat_at", session.lastHeartbeatAt().atOffset(ZoneOffset.UTC).toString());
                send(session.socketSession(), AgentMessageType.HEARTBEAT_ACK, agentMessage.messageId(),
                        ackPayload);
            } else if ("metrics.report".equals(agentMessage.type()) && session.authenticated()) {
                if (messageRateLimiter != null && !messageRateLimiter.allowMetrics(session.sessionId())) {
                    sendErrorAndClose(session.socketSession(), agentMessage.messageId(),
                            ErrorCode.AGENT_MESSAGE_RATE_LIMIT_REACHED);
                    return;
                }
                if (!isUuid(agentMessage.messageId())) {
                    sendError(session.socketSession(), null, ErrorCode.INVALID_REQUEST_PARAMETER);
                    return;
                }
                MetricsReportPayload reportPayload = objectMapper.treeToValue(
                        agentMessage.payload(), MetricsReportPayload.class);
                metricsService.report(session.serverId(), agentMessage.messageId(), reportPayload);
                // 仅在指标事务成功返回后确认入口持久化；异步 Outbox 发布和告警评估不属于本帧语义。
                var ackPayload = objectMapper.createObjectNode()
                        .put("server_id", session.serverId())
                        .put("collected_at", reportPayload.getCollectedAt().toString());
                send(session.socketSession(), AgentMessageType.METRICS_ACK, agentMessage.messageId(), ackPayload);
            } else if (agentMessage.type() != null && agentMessage.type().startsWith("terminal.")
                    && session.authenticated()) {
                if (terminalRelayService == null) {
                    throw new BusinessException(ErrorCode.TERMINAL_AGENT_OFFLINE);
                }
                terminalRelayService.relay(session, objectMapper.treeToValue(
                        objectMapper.valueToTree(agentMessage), TerminalMessage.class));
            } else {
                sendError(session.socketSession(), agentMessage.messageId(), ErrorCode.INVALID_REQUEST_PARAMETER);
            }
        } catch (BusinessException exception) {
            sendError(socketSession, null, exception.getErrorCode());
            if (!session.authenticated()) {
                close(socketSession, CloseStatus.POLICY_VIOLATION);
            }
        } catch (IllegalStateException exception) {
            // 心跳目标不可用等运行时状态异常单独处理，不误报为消息格式错误，也不关闭已认证连接。
            log.warn("agent runtime state error, sessionId={}, message={}", socketSession.getId(),
                    exception.getMessage());
            sendError(socketSession, null, ErrorCode.INTERNAL_SERVER_ERROR);
        } catch (Exception exception) {
            sendError(socketSession, null, ErrorCode.BAD_REQUEST);
            close(socketSession, CloseStatus.BAD_DATA);
        }
    }

    /** 连接关闭时清理待认证或已认证会话。 */
    @Override
    public void afterConnectionClosed(WebSocketSession socketSession, CloseStatus status) {
        AgentWebSocketSession session = pendingSessions.remove(socketSession.getId());
        if (session != null && session.authenticated()) {
            if (terminalRelayLifecycleService != null) {
                terminalRelayLifecycleService.closeAgentSessions(session);
            }
            // Agent 断开(关机/进程退出)时立即标记服务器离线;
            // 乐观锁防止误覆盖已重连新连接(markAgentOffline 要求 last_heartbeat_at 匹配)。
            if (heartbeatService != null) {
                heartbeatService.markOfflineOnDisconnect(session);
            }
            connectionRegistry.remove(session);
        }
        if (connectionLimiter != null) {
            connectionLimiter.release(socketSession.getId());
        }
        if (messageRateLimiter != null) {
            messageRateLimiter.release(socketSession.getId());
        }
    }

    private void authenticate(AgentWebSocketSession session, JsonNode payload) throws IOException {
        if (session.authenticated() || payload == null
                || !payload.hasNonNull("server_id") || !payload.hasNonNull("token")
                || !payload.get("token").isTextual() || payload.get("token").textValue().isBlank()) {
            throw new BusinessException(com.susumonitor.server.common.ErrorCode.UNAUTHORIZED);
        }
        // 标记认证中，避免超时扫描在 DB 认证未完成时关闭连接。
        session.markAuthenticating();
        Long serverId = payload.get("server_id").longValue();
        ServerEntity server = authenticationService.authenticate(serverId, payload.get("token").textValue());
        session.authenticate(server.getId(), LocalDateTime.now(clock));
        if (connectionLimiter != null) {
            connectionLimiter.authenticate(session.sessionId());
        }
        heartbeatService.heartbeat(session);
        // 注册前校验连接仍在，避免把已关闭会话注册进 registry。
        if (session.socketSession().isOpen()) {
            connectionRegistry.replace(session).ifPresent(old -> close(old.socketSession(), CloseStatus.NORMAL));
            // 冻结 agent.authenticated payload，返回 server_id 和认证时间，供 Agent 校验绑定。
            var authPayload = objectMapper.createObjectNode()
                    .put("server_id", server.getId())
                    .put("authenticated_at", OffsetDateTime.now(clock).toString());
            send(session.socketSession(), AgentMessageType.AGENT_AUTHENTICATED, null, authPayload);
        }
    }

    /** 清理十秒内未发送认证首帧的连接，跳过认证中状态避免误杀慢 DB 认证。 */
    @Scheduled(fixedDelay = 5_000)
    public void closeUnauthenticatedSessions() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofSeconds(10));
        pendingSessions.values().removeIf(session -> {
            if (session.authenticated() || session.authenticating() || session.connectedAt().isAfter(cutoff)) {
                return false;
            }
            close(session.socketSession(), CloseStatus.POLICY_VIOLATION);
            return true;
        });
    }

    private void send(WebSocketSession session, AgentMessageType type, String messageId, JsonNode payload)
            throws IOException {
        if (session.isOpen()) {
            String body = objectMapper.createObjectNode()
                    .put("type", type.value())
                    .put("message_id", messageId)
                    .put("timestamp", OffsetDateTime.now(clock).toString())
                    .set("payload", payload).toString();
            session.sendMessage(new TextMessage(body));
        }
    }

    /** metrics.report 使用 UUID 作为幂等键，格式无效时不进入指标事务。 */
    private boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 发送带业务错误码的 error 帧，供 Agent 客户端按 code 做稳定恢复。
     *
     * @param session 目标 WebSocket 会话
     * @param messageId 关联客户端消息 ID，没有则为 null
     * @param errorCode 业务错误码，payload 包含 code 和 message
     * @throws IOException 发送失败
     */
    private void sendError(WebSocketSession session, String messageId, ErrorCode errorCode) throws IOException {
        send(session, AgentMessageType.ERROR, messageId,
                objectMapper.createObjectNode()
                        .put("code", errorCode.getCode())
                        .put("message", errorCode.getMessage()));
    }

    /** 速率超限后先返回稳定业务码，再关闭连接以阻止持续解析和数据库访问。 */
    private void sendErrorAndClose(WebSocketSession session, String messageId, ErrorCode errorCode) throws IOException {
        sendError(session, messageId, errorCode);
        // Tomcat 异步写入 WebSocket 帧，延迟关闭确保客户端先接收可恢复的业务码。
        if (taskScheduler != null) {
            taskScheduler.schedule(() -> close(session, CloseStatus.POLICY_VIOLATION),
                    Instant.now(clock).plus(ERROR_CLOSE_DELAY));
            return;
        }
        close(session, CloseStatus.POLICY_VIOLATION);
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            log.debug("Agent WebSocket close failed, sessionId={}", session.getId());
        }
    }
}

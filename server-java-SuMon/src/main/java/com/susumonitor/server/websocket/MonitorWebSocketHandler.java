package com.susumonitor.server.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.security.AuthenticatedUser;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 处理已通过一次性 ticket 握手的浏览器指标订阅与终端中继。
 *
 * <p>当前授权策略：
 * <ul>
 *   <li>指标订阅：admin 或 review_status=approved 的已认证用户均可订阅任意存在的服务器指标。
 *       上游 JWT 过滤器强制要求所有通过认证的用户 review_status=approved，故此处 approved 判断恒为真，
 *       实际起作用的是 role=admin 的分支；该判断保留以明确授权意图，便于后续按服务器粒度收紧。</li>
 *   <li>终端通道：与 REST 面 SSH 操作一致，仅 admin 可对服务器打开交互式终端会话；
 *       普通用户发送 terminal.* 帧回 40302 error 帧并保留连接。</li>
 * </ul></p>
 */
@Component
public class MonitorWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ServerMapper serverMapper;
    private final MonitorSubscriptionRegistry registry;
    private final Clock clock;
    private final TerminalMonitorRelayService terminalRelayService;
    private final TerminalRelayLifecycleService terminalRelayLifecycleService;
    private final TerminalMessageRateLimiter terminalMessageRateLimiter;
    private final MonitorSessionTerminationService terminationService;
    private final AppProperties appProperties;
    private final Map<String, MonitorWebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 注入 JSON、服务器权限查询和订阅注册表。 */
    @Autowired
    public MonitorWebSocketHandler(ObjectMapper objectMapper, ServerMapper serverMapper,
            MonitorSubscriptionRegistry registry, Clock clock, TerminalMonitorRelayService terminalRelayService,
            TerminalRelayLifecycleService terminalRelayLifecycleService,
            TerminalMessageRateLimiter terminalMessageRateLimiter, MonitorSessionTerminationService terminationService,
            AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.serverMapper = serverMapper;
        this.registry = registry;
        this.clock = clock;
        this.terminalRelayService = terminalRelayService;
        this.terminalRelayLifecycleService = terminalRelayLifecycleService;
        this.terminalMessageRateLimiter = terminalMessageRateLimiter;
        this.terminationService = terminationService;
        this.appProperties = appProperties;
    }

    MonitorWebSocketHandler(ObjectMapper objectMapper, ServerMapper serverMapper,
            MonitorSubscriptionRegistry registry, Clock clock) {
        this(objectMapper, serverMapper, registry, clock, null, null, null, null, new AppProperties());
    }

    /** 保持既有隔离测试可按终端中继依赖构造 Handler。 */
    MonitorWebSocketHandler(ObjectMapper objectMapper, ServerMapper serverMapper,
            MonitorSubscriptionRegistry registry, Clock clock, TerminalMonitorRelayService terminalRelayService,
            TerminalRelayLifecycleService terminalRelayLifecycleService,
            TerminalMessageRateLimiter terminalMessageRateLimiter) {
        this(objectMapper, serverMapper, registry, clock, terminalRelayService, terminalRelayLifecycleService,
                terminalMessageRateLimiter, null, new AppProperties());
    }

    /** 从握手属性取得用户身份并注册连接。 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Object principal = session.getAttributes().get("authenticated_user");
        if (!(principal instanceof AuthenticatedUser user)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        WebSocketSession protectedSession = new ConcurrentWebSocketSessionDecorator(session,
                appProperties.getTerminal().getMonitorSendTimeLimitMillis(),
                appProperties.getTerminal().getMonitorBufferSizeBytes(),
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        MonitorWebSocketSession monitorSession = new MonitorWebSocketSession(protectedSession, user);
        sessions.put(session.getId(), monitorSession);
        registry.register(monitorSession);
    }

    /** 处理 metrics.subscribe 和 metrics.unsubscribe 消息，坏 JSON 回送错误帧而非断连。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        MonitorWebSocketSession monitorSession = sessions.get(session.getId());
        if (monitorSession == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(message.getPayload());
        } catch (JsonProcessingException exception) {
            // 一条格式错误的 JSON 不应让整个 Monitor 连接断开，回送错误帧后保留连接。
            sendError(monitorSession, null, ErrorCode.BAD_REQUEST);
            return;
        }
        String type = body == null || !body.has("type") || !body.get("type").isTextual()
                ? "" : body.get("type").textValue();
        // 从消息体提取 message_id，用于 error 帧关联客户端请求。
        String messageId = body != null && body.has("message_id") && body.get("message_id").isTextual()
                ? body.get("message_id").textValue() : null;
        if (type.startsWith("terminal.")) {
            // 终端通道与 REST 面 SSH 一致，仅 admin 可打开交互式会话（40302），
            // 避免普通用户通过 WS 通道绕过 REST 的 admin 限制获得服务器 Shell。
            if (!"admin".equals(monitorSession.user().role())) {
                sendError(monitorSession, messageId, ErrorCode.TERMINAL_ACCESS_DENIED);
                return;
            }
            try {
                TerminalMessage terminalMessage = objectMapper.treeToValue(body, TerminalMessage.class);
                TerminalProtocolValidator.validateMonitorMessage(terminalMessage);
                if (terminalRelayService == null) {
                    throw new BusinessException(ErrorCode.TERMINAL_AGENT_OFFLINE);
                }
                if (terminalMessageRateLimiter != null && !terminalMessageRateLimiter.allow(monitorSession, terminalMessage)) {
                    throw new BusinessException(ErrorCode.TERMINAL_MESSAGE_LIMIT_REACHED);
                }
                terminalRelayService.relay(monitorSession, terminalMessage);
            } catch (BusinessException exception) {
                sendError(monitorSession, messageId, exception.getErrorCode());
            }
            return;
        }
        // path 在缺失时返回 MissingNode 而非 null，用 isMissingNode 表意更清晰。
        JsonNode serverNode = body == null ? null : body.path("payload").path("server_id");
        if (serverNode == null || serverNode.isMissingNode()
                || !serverNode.canConvertToLong() || serverNode.longValue() <= 0) {
            // server_id 缺失或非法属于协议格式违规，发 error 帧后关闭连接。
            sendError(monitorSession, messageId, ErrorCode.INVALID_REQUEST_PARAMETER);
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        Long serverId = serverNode.longValue();
        if (serverMapper.selectActiveServerById(serverId) == null) {
            // 服务器不存在，发 error 帧保留连接，供前端修正后重试。
            sendError(monitorSession, messageId, ErrorCode.RESOURCE_NOT_FOUND);
            return;
        }
        // 当前授权策略：admin 或 approved 用户均可订阅，详见类注释。
        if (!"admin".equals(monitorSession.user().role())
                && !"approved".equals(monitorSession.user().reviewStatus())) {
            // 权限不足，发 error 帧保留连接。
            sendError(monitorSession, messageId, ErrorCode.FORBIDDEN);
            return;
        }
        if ("metrics.subscribe".equals(type)) {
            registry.subscribe(monitorSession, serverId);
        } else if ("metrics.unsubscribe".equals(type)) {
            registry.unsubscribe(monitorSession, serverId);
        } else {
            // 未知消息类型，发 error 帧保留连接。
            sendError(monitorSession, messageId, ErrorCode.BAD_REQUEST);
        }
    }

    /** 清理连接及其全部服务器订阅。 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        MonitorWebSocketSession monitorSession = sessions.remove(session.getId());
        if (monitorSession != null) {
            terminateNormally(monitorSession);
            if (terminationService == null && terminalMessageRateLimiter != null) {
                terminalMessageRateLimiter.release(monitorSession);
            }
        }
    }

    /**
     * 向 Monitor 连接回送带业务错误码的 error 帧，不关闭连接。
     *
     * @param session 目标 WebSocket 会话
     * @param messageId 关联客户端消息 ID，没有则生成随机 UUID
     * @param errorCode 业务错误码，payload 包含 code 和 message
     * @throws IOException 发送失败
     */
    private void sendError(MonitorWebSocketSession session, String messageId, ErrorCode errorCode) throws IOException {
        try {
            String body = objectMapper.createObjectNode()
                    .put("type", "error")
                    .put("message_id", messageId != null ? messageId : UUID.randomUUID().toString())
                    .put("timestamp", OffsetDateTime.now(clock).toString())
                    .set("payload", objectMapper.createObjectNode()
                            .put("code", errorCode.getCode())
                            .put("message", errorCode.getMessage()))
                    .toString();
            session.send(new TextMessage(body));
        } catch (MonitorBackpressureException exception) {
            terminateForBackpressure(session);
        }
    }

    /** 在测试替身缺少完整协调组件时保留原有普通收口路径。 */
    private void terminateNormally(MonitorWebSocketSession monitorSession) {
        if (terminationService != null) {
            terminationService.terminateNormally(monitorSession);
        } else if (terminalRelayLifecycleService != null) {
            terminalRelayLifecycleService.closeMonitorSessions(monitorSession);
        }
        registry.remove(monitorSession);
    }

    /** 将发送限额耗尽统一转换为背压收口，不能把运行时异常泄漏给 WebSocket 容器。 */
    private void terminateForBackpressure(MonitorWebSocketSession monitorSession) {
        if (terminationService != null) {
            terminationService.terminateForBackpressure(monitorSession);
        } else {
            terminateNormally(monitorSession);
        }
    }
}

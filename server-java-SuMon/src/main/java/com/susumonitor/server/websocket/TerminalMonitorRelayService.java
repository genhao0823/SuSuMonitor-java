package com.susumonitor.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.terminal.entity.TerminalSessionEntity;
import com.susumonitor.server.module.terminal.service.TerminalSessionService;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/** 将已校验的浏览器终端控制帧定向中继到对应 Agent，不处理终端内容。 */
@Component
public class TerminalMonitorRelayService {
    private final TerminalSessionService terminalSessionService;
    private final TerminalRelayRegistry relayRegistry;
    private final AgentConnectionRegistry agentRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 注入终端会话服务、路由注册表、Agent 连接注册表、JSON 序列化器和时钟。 */
    public TerminalMonitorRelayService(TerminalSessionService terminalSessionService, TerminalRelayRegistry relayRegistry,
            AgentConnectionRegistry agentRegistry, ObjectMapper objectMapper, Clock clock) {
        this.terminalSessionService = terminalSessionService;
        this.relayRegistry = relayRegistry;
        this.agentRegistry = agentRegistry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 根据浏览器消息创建或验证会话并转发给目标 Agent。 */
    public void relay(MonitorWebSocketSession monitorSession, TerminalMessage message) throws IOException {
        TerminalSessionEntity session;
        if (TerminalMessageType.TERMINAL_OPEN.value().equals(message.type())) {
            Long serverId = message.payload().get("server_id").longValue();
            session = terminalSessionService.openSession(monitorSession.user().id(), serverId, message.messageId());
            if (!relayRegistry.bind(session.getSessionId(), serverId, monitorSession)) {
                throw new BusinessException(ErrorCode.TERMINAL_SESSION_STATE_CONFLICT);
            }
        } else {
            String sessionId = message.payload().get("session_id").textValue();
            session = terminalSessionService.requireActiveSession(monitorSession.user().id(), sessionId);
            TerminalRelayRegistry.TerminalRelayBinding binding = relayRegistry.get(sessionId);
            if (binding == null || !binding.monitorSession().equals(monitorSession)
                    || !binding.serverId().equals(session.getServerId())) {
                throw new BusinessException(ErrorCode.TERMINAL_ACCESS_DENIED);
            }
            terminalSessionService.touchSession(sessionId);
        }
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) message.payload().deepCopy();
        payload.put("server_id", session.getServerId());
        payload.put("session_id", session.getSessionId());
        String body = objectMapper.createObjectNode().put("type", message.type())
                .put("message_id", message.messageId()).put("timestamp", OffsetDateTime.now(clock).toString())
                .set("payload", payload).toString();
        if (!agentRegistry.sendToServer(session.getServerId(), new TextMessage(body))) {
            throw new BusinessException(ErrorCode.TERMINAL_AGENT_OFFLINE);
        }
    }
}

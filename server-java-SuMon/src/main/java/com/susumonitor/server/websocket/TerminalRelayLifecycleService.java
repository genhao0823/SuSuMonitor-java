package com.susumonitor.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.terminal.enums.TerminalSessionStatus;
import com.susumonitor.server.module.terminal.service.TerminalSessionService;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/** 在 WebSocket 断开时收口终端元数据、内存路由和远端 PTY。 */
@Component
public class TerminalRelayLifecycleService {
    private final TerminalSessionService terminalSessionService;
    private final TerminalRelayRegistry relayRegistry;
    private final AgentConnectionRegistry agentRegistry;
    private final TerminalOutputRateLimiter outputRateLimiter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 注入终端会话服务、内存路由注册表、Agent 连接注册表、输出限流器、JSON 序列化器和时钟。 */
    public TerminalRelayLifecycleService(TerminalSessionService terminalSessionService, TerminalRelayRegistry relayRegistry,
            AgentConnectionRegistry agentRegistry, TerminalOutputRateLimiter outputRateLimiter, ObjectMapper objectMapper,
            Clock clock) {
        this.terminalSessionService = terminalSessionService;
        this.relayRegistry = relayRegistry;
        this.agentRegistry = agentRegistry;
        this.outputRateLimiter = outputRateLimiter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 浏览器断开后通知在线 Agent 关闭 PTY，并收口对应会话元数据。 */
    public void closeMonitorSessions(MonitorWebSocketSession monitorSession) {
        closeMonitorSessions(monitorSession, "monitor_disconnected");
    }

    /** 浏览器因指定原因关闭后通知在线 Agent 关闭 PTY，并收口对应会话元数据。 */
    public void closeMonitorSessions(MonitorWebSocketSession monitorSession, String reason) {
        for (TerminalRelayRegistry.TerminalRelayBinding binding : relayRegistry.removeByMonitorSession(monitorSession)) {
            sendClose(binding);
            closeBinding(binding, TerminalSessionStatus.CLOSED.value(), reason);
        }
    }

    /** 当前 Agent 断开后将其未完成会话标记为错误；被替换的旧连接不触发收口。 */
    public void closeAgentSessions(AgentWebSocketSession agentSession) {
        if (!agentRegistry.isCurrent(agentSession)) {
            return;
        }
        for (TerminalRelayRegistry.TerminalRelayBinding binding : relayRegistry.removeByServerId(agentSession.serverId())) {
            closeBinding(binding, TerminalSessionStatus.ERROR.value(), "agent_disconnected");
        }
    }

    /**
     * 以统一顺序收口会话元数据、内存路由与输出令牌桶，供正常关闭和异常关闭复用。
     *
     * @param binding 已验证的会话路由绑定
     * @param status 持久化的终端会话状态
     * @param reason 持久化的关闭原因
     */
    public void closeBinding(TerminalRelayRegistry.TerminalRelayBinding binding, String status, String reason) {
        relayRegistry.remove(binding.sessionId());
        outputRateLimiter.release(binding.sessionId());
        terminalSessionService.closeSession(binding.sessionId(), status, reason);
    }

    /** 服务端主动关闭 PTY 后统一收口本地会话状态，供输出带宽超额等保护措施调用。 */
    public void closeBindingFromServer(TerminalRelayRegistry.TerminalRelayBinding binding, String status, String reason) {
        sendClose(binding);
        closeBinding(binding, status, reason);
    }

    /** 断连清理使用服务端生成的关闭帧，失败后仍必须完成元数据收口。 */
    private void sendClose(TerminalRelayRegistry.TerminalRelayBinding binding) {
        String body = objectMapper.createObjectNode().put("type", TerminalMessageType.TERMINAL_CLOSE.value())
                .put("message_id", UUID.randomUUID().toString()).put("timestamp", OffsetDateTime.now(clock).toString())
                .set("payload", objectMapper.createObjectNode().put("server_id", binding.serverId())
                        .put("session_id", binding.sessionId())).toString();
        try {
            agentRegistry.sendToServer(binding.serverId(), new TextMessage(body));
        } catch (IOException ignored) {
            // Agent 已断开时仍应关闭元数据，避免遗留可操作会话。
        }
    }
}

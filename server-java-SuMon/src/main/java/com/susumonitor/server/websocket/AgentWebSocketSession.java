package com.susumonitor.server.websocket;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import java.io.IOException;

/**
 * 保存 Agent WebSocket 的认证归属和最近心跳，不保存明文 Token。
 */
public final class AgentWebSocketSession {

    private final WebSocketSession socketSession;
    private final String sessionId;
    private final Instant connectedAt;
    private Long serverId;
    private LocalDateTime lastHeartbeatAt;
    // 认证进行中标记：避免认证未完成时被未认证超时扫描误关闭。
    private volatile boolean authenticating;
    private boolean authenticated;

    /** 创建未认证的 WebSocket 会话包装。 */
    public AgentWebSocketSession(WebSocketSession socketSession, Clock clock) {
        this.socketSession = socketSession;
        this.sessionId = socketSession.getId();
        this.connectedAt = Instant.now(clock);
    }

    /** 返回底层 Spring WebSocket 会话。 */
    public WebSocketSession socketSession() {
        return socketSession;
    }

    /** 返回 WebSocket 会话 ID。 */
    public String sessionId() {
        return sessionId;
    }

    /** 返回连接建立的时刻。 */
    public Instant connectedAt() {
        return connectedAt;
    }

    /** 返回 Agent 认证后关联的服务器 ID。 */
    public Long serverId() {
        return serverId;
    }

    /** 完成认证，设置服务器 ID 和首次心跳时间。 */
    public void authenticate(Long serverId, LocalDateTime heartbeatAt) {
        this.serverId = serverId;
        this.lastHeartbeatAt = heartbeatAt;
        this.authenticated = true;
    }

    /** 标记会话进入认证中状态，防止超时扫描在认证未完成时关闭连接。 */
    public void markAuthenticating() {
        this.authenticating = true;
    }

    /** 返回会话是否正在认证中。 */
    public boolean authenticating() {
        return authenticating;
    }

    /** 返回会话是否已完成认证。 */
    public boolean authenticated() {
        return authenticated;
    }

    /** 返回最近一次心跳的时间。 */
    public LocalDateTime lastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    /** 更新最近一次心跳时间。 */
    public void heartbeat(LocalDateTime heartbeatAt) {
        this.lastHeartbeatAt = heartbeatAt;
    }

    /** 串行化对单个 Agent Socket 的写入，避免并发中继帧交错。 */
    public synchronized boolean send(TextMessage message) throws IOException {
        if (!socketSession.isOpen()) {
            return false;
        }
        socketSession.sendMessage(message);
        return true;
    }
}

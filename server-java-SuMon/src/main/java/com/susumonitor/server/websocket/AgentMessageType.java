package com.susumonitor.server.websocket;

/**
 * Agent WebSocket 第一版支持的消息类型。
 */
public enum AgentMessageType {
    AGENT_AUTHENTICATE("agent.authenticate"),
    AGENT_AUTHENTICATED("agent.authenticated"),
    HEARTBEAT("heartbeat"),
    HEARTBEAT_ACK("heartbeat.ack"),
    METRICS_ACK("metrics.ack"),
    METRICS_NACK("metrics.nack"),
    ERROR("error");

    private final String value;

    AgentMessageType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

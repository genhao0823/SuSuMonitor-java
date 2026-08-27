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

    /**
     * 创建 Agent 消息类型枚举项。
     *
     * @param value 协议中的稳定字符串值
     */
    AgentMessageType(String value) {
        this.value = value;
    }

    /**
     * 返回协议中使用的稳定消息类型字符串。
     *
     * @return 消息类型字符串
     */
    public String value() {
        return value;
    }
}

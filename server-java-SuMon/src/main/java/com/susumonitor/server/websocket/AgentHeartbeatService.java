package com.susumonitor.server.websocket;

/**
 * 定义 Agent 心跳更新和过期会话扫描的运行时契约。
 */
public interface AgentHeartbeatService {

    /** 处理已认证 Agent 心跳。 */
    void heartbeat(AgentWebSocketSession session);

    /** 处理已认证 Agent 心跳，并持久化心跳携带的投递遥测统计。 */
    void heartbeat(AgentWebSocketSession session, AgentHeartbeatPayload deliveryStats);

    /** 扫描过期会话并标记对应服务器离线。 */
    void markExpiredSessionsOffline();

    /**
     * Agent 连接断开时标记对应服务器离线。
     *
     * <p>使用乐观锁:仅当 DB last_heartbeat_at 仍是断开时记录的值才设 offline,
     * 防止误把已重连新连接(新心跳已更新 last_heartbeat_at)设为离线。
     * 场景:agent 关机/进程退出后,afterConnectionClosed 立即更新 DB status=offline,
     * 避免服务器在 agent 离线后仍显示在线。
     */
    void markOfflineOnDisconnect(AgentWebSocketSession session);
}

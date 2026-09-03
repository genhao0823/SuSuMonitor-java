package com.susumonitor.server.module.command;

/**
 * 定义命令下发出站端口：module/command 不直接依赖 WebSocket 具体类，
 * 由 websocket 包提供基于已认证 Agent 连接的实现，避免模块间循环依赖。
 */
public interface CommandTransport {

    /**
     * 向目标服务器的已认证 Agent 连接发送一条 JSON 帧文本。
     *
     * @param serverId 目标服务器 ID
     * @param jsonBody 完整消息 JSON（含 type/message_id/timestamp/payload）
     * @return 发送成功返回 true；Agent 不在线或发送失败返回 false
     */
    boolean send(Long serverId, String jsonBody);
}

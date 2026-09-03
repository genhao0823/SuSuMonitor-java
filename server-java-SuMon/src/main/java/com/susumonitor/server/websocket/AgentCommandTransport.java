package com.susumonitor.server.websocket;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.command.CommandTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基于 Agent WebSocket 连接的命令下发实现（module/command 出站端口的 WS 适配）。
 *
 * <p>仅在命令域启用时装配；Agent 离线或连接写入失败返回 false，由状态机
 * 置 failed(40906)。Java 侧生成 message_id（UUID），Agent 以之关联 command.result。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class AgentCommandTransport implements CommandTransport {

    private final AgentConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 组装 command.execute 帧并经已认证连接发送。 */
    @Override
    public boolean send(Long serverId, String jsonBody) {
        ObjectNode frame;
        try {
            frame = (ObjectNode) objectMapper.readTree(jsonBody);
        } catch (Exception exception) {
            // jsonBody 由状态机受控字段构造，此分支防御性拒绝而非透传。
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
        }
        frame.put("message_id", UUID.randomUUID().toString());
        frame.put("timestamp", OffsetDateTime.now(clock).toString());
        try {
            return connectionRegistry.sendToServer(serverId, new org.springframework.web.socket.TextMessage(frame.toString()));
        } catch (Exception exception) {
            return false;
        }
    }
}

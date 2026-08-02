package com.susumonitor.server.websocket;

import com.susumonitor.server.module.server.mapper.ServerMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;

/**
 * 更新 Agent 心跳并扫描超过 90 秒未心跳的会话。
 */
@Service
public class AgentHeartbeatServiceImpl implements AgentHeartbeatService {

    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(90);
    private final ServerMapper serverMapper;
    private final AgentConnectionRegistry connectionRegistry;
    private final MonitorServerStatusPublisher statusPublisher;
    private final Clock clock;

    /** 注入服务器状态 Mapper、Agent 连接注册表和 Monitor 状态发布器。 */
    @Autowired
    public AgentHeartbeatServiceImpl(ServerMapper serverMapper, AgentConnectionRegistry connectionRegistry,
            MonitorServerStatusPublisher statusPublisher, Clock clock) {
        this.serverMapper = serverMapper;
        this.connectionRegistry = connectionRegistry;
        this.statusPublisher = statusPublisher;
        this.clock = clock;
    }

    /** 保留心跳边界单测所需的最小构造入口。 */
    AgentHeartbeatServiceImpl(ServerMapper serverMapper, AgentConnectionRegistry connectionRegistry, Clock clock) {
        this(serverMapper, connectionRegistry, null, clock);
    }

    /** 处理已认证 Agent 心跳，并仅在首次上线时广播状态转换。 */
    public void heartbeat(AgentWebSocketSession session) {
        LocalDateTime heartbeatAt = LocalDateTime.now(clock);
        int onlineTransitioned = serverMapper.markAgentOnlineAndHeartbeat(session.serverId(), heartbeatAt);
        if (onlineTransitioned != 1
                && serverMapper.updateAgentHeartbeat(session.serverId(), heartbeatAt) != 1) {
            throw new IllegalStateException("Agent heartbeat target is unavailable");
        }
        session.heartbeat(heartbeatAt);
        if (onlineTransitioned == 1) {
            publish(session.serverId(), "online", "online", heartbeatAt);
        }
    }

    /** 每 30 秒扫描过期会话并标记服务器离线。 */
    @Scheduled(fixedDelay = 30_000)
    public void markExpiredSessionsOffline() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(HEARTBEAT_TIMEOUT);
        for (AgentWebSocketSession session : connectionRegistry.sessions()) {
            if (session.authenticated() && session.lastHeartbeatAt() != null
                    && session.lastHeartbeatAt().isBefore(cutoff)) {
                int offlineTransitioned = serverMapper.markAgentOffline(session.serverId(), session.lastHeartbeatAt());
                if (offlineTransitioned == 1) {
                    publish(session.serverId(), "offline", "offline", session.lastHeartbeatAt());
                }
                connectionRegistry.remove(session);
                if (session.socketSession().isOpen()) {
                    try {
                        session.socketSession().close(CloseStatus.SESSION_NOT_RELIABLE);
                    } catch (Exception ignored) {
                        // 扫描任务继续处理其他会话。
                    }
                }
            }
        }
    }

    /** Agent 连接断开时标记服务器离线(乐观锁,不覆盖已重连新连接)。 */
    @Override
    public void markOfflineOnDisconnect(AgentWebSocketSession session) {
        if (session == null || !session.authenticated() || session.serverId() == null
                || session.lastHeartbeatAt() == null) {
            return;
        }
        // 乐观锁:仅当 last_heartbeat_at 仍是断开时的值才设 offline,
        // 防止误把已重连新连接(新心跳更新了 last_heartbeat_at)设为离线。
        int offlineTransitioned = serverMapper.markAgentOffline(session.serverId(), session.lastHeartbeatAt());
        if (offlineTransitioned == 1) {
            publish(session.serverId(), "offline", "offline", session.lastHeartbeatAt());
        }
    }

    /** 有 Monitor 订阅者时发送状态转换；单元测试构造器不注入发布器。 */
    private void publish(Long serverId, String status, String agentStatus, LocalDateTime lastHeartbeatAt) {
        if (statusPublisher != null) {
            statusPublisher.publish(serverId, status, agentStatus, lastHeartbeatAt);
        }
    }
}

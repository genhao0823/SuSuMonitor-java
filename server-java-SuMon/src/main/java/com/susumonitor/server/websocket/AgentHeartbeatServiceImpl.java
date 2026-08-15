package com.susumonitor.server.websocket;

import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;

/**
 * 更新 Agent 心跳并扫描超过心跳超时阈值（默认 90 秒）未心跳的会话。
 */
@Service
public class AgentHeartbeatServiceImpl implements AgentHeartbeatService {

    private final Duration heartbeatTimeout;
    private final ServerMapper serverMapper;
    private final AgentConnectionRegistry connectionRegistry;
    private final MonitorServerStatusPublisher statusPublisher;
    private final TerminalRelayLifecycleService terminalRelayLifecycleService;
    private final Clock clock;

    /** 注入服务器状态 Mapper、Agent 连接注册表、Monitor 状态发布器、时钟、心跳超时配置与终端中继收口服务。 */
    @Autowired
    public AgentHeartbeatServiceImpl(ServerMapper serverMapper, AgentConnectionRegistry connectionRegistry,
            MonitorServerStatusPublisher statusPublisher, Clock clock, AppProperties appProperties,
            TerminalRelayLifecycleService terminalRelayLifecycleService) {
        this(serverMapper, connectionRegistry, statusPublisher, clock,
                Duration.ofSeconds(appProperties.getAgent().getHeartbeatTimeoutSeconds()),
                terminalRelayLifecycleService);
    }

    /** 保留心跳边界单测所需的最小构造入口，心跳超时取默认 90 秒。 */
    AgentHeartbeatServiceImpl(ServerMapper serverMapper, AgentConnectionRegistry connectionRegistry,
            MonitorServerStatusPublisher statusPublisher, Clock clock,
            TerminalRelayLifecycleService terminalRelayLifecycleService) {
        this(serverMapper, connectionRegistry, statusPublisher, clock, Duration.ofSeconds(90),
                terminalRelayLifecycleService);
    }

    private AgentHeartbeatServiceImpl(ServerMapper serverMapper, AgentConnectionRegistry connectionRegistry,
            MonitorServerStatusPublisher statusPublisher, Clock clock, Duration heartbeatTimeout,
            TerminalRelayLifecycleService terminalRelayLifecycleService) {
        this.serverMapper = serverMapper;
        this.connectionRegistry = connectionRegistry;
        this.statusPublisher = statusPublisher;
        this.clock = clock;
        this.heartbeatTimeout = heartbeatTimeout;
        this.terminalRelayLifecycleService = terminalRelayLifecycleService;
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

    /** 处理已认证 Agent 心跳，并持久化心跳携带的投递遥测统计。 */
    public void heartbeat(AgentWebSocketSession session, AgentHeartbeatPayload deliveryStats) {
        heartbeat(session);
        if (deliveryStats == null || !deliveryStats.hasDeliveryStats()) {
            return;
        }
        // 遥测统计是附属数据：更新失败不打断心跳，也不误报心跳目标不可用。
        serverMapper.updateDeliveryStats(session.serverId(),
                toUtcLocalDateTime(deliveryStats.oldestCollectedAt()),
                deliveryStats.pendingCount(), deliveryStats.pendingBytes(), deliveryStats.dropCount(),
                deliveryStats.deadLetterCount(), deliveryStats.deadLetterBytes());
    }

    /** 将 UTC 时刻转换为数据库存储的 LocalDateTime。 */
    /** 将 UTC OffsetDateTime 转换为数据库使用的 LocalDateTime。 */
    private static LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** 每 30 秒扫描过期会话：先收口其终端会话，再标记离线并关闭连接。 */
    @Scheduled(fixedDelay = 30_000)
    public void markExpiredSessionsOffline() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(heartbeatTimeout);
        for (AgentWebSocketSession session : connectionRegistry.sessions()) {
            if (session.authenticated() && session.lastHeartbeatAt() != null
                    && session.lastHeartbeatAt().isBefore(cutoff)) {
                // 注册表仍指向该会话时先收口中继：isCurrent 判定通过，向浏览器推 closed 帧并收口元数据；
                // 随后 remove 与关闭 socket 触发的 afterConnectionClosed 因绑定已清空而幂等。
                terminalRelayLifecycleService.closeAgentSessions(session);
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

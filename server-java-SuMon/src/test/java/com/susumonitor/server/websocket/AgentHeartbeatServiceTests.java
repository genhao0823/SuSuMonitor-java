package com.susumonitor.server.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.server.mapper.ServerMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 验证 Agent 九十秒心跳离线边界。 */
class AgentHeartbeatServiceTests {

    private static final Instant HEARTBEAT_AT = Instant.parse("2026-07-22T00:00:00Z");
    private static final Long SERVER_ID = 1001L;

    /** 验证 89 秒不离线，91 秒标记离线并关闭连接。 */
    @Test
    void shouldMarkOfflineOnlyAfterHeartbeatTimeout() throws Exception {
        MutableClock clock = new MutableClock(HEARTBEAT_AT.plusSeconds(89));
        ServerMapper serverMapper = mock(ServerMapper.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        TerminalRelayLifecycleService relay = mock(TerminalRelayLifecycleService.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("heartbeat-session");
        when(socket.isOpen()).thenReturn(true);
        AgentWebSocketSession session = new AgentWebSocketSession(socket, clock);
        session.authenticate(SERVER_ID, LocalDateTime.ofInstant(HEARTBEAT_AT, ZoneOffset.UTC));
        when(registry.sessions()).thenReturn(List.of(session));
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, null, clock, relay);

        service.markExpiredSessionsOffline();
        verify(serverMapper, never()).markAgentOffline(SERVER_ID, session.lastHeartbeatAt());

        clock.set(HEARTBEAT_AT.plusSeconds(91));
        service.markExpiredSessionsOffline();
        verify(serverMapper).markAgentOffline(SERVER_ID, session.lastHeartbeatAt());
        verify(registry).remove(session);
        verify(socket).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    /** 验证心跳超时路径先收口中继会话再移除注册表，浏览器会话能收到关闭帧。 */
    @Test
    void shouldCloseAgentRelaySessionsBeforeRemovingExpiredSession() throws Exception {
        MutableClock clock = new MutableClock(HEARTBEAT_AT.plusSeconds(91));
        ServerMapper serverMapper = mock(ServerMapper.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        TerminalRelayLifecycleService relay = mock(TerminalRelayLifecycleService.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("heartbeat-relay-session");
        AgentWebSocketSession session = new AgentWebSocketSession(socket, clock);
        session.authenticate(SERVER_ID, LocalDateTime.ofInstant(HEARTBEAT_AT, ZoneOffset.UTC));
        when(registry.sessions()).thenReturn(List.of(session));
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, null, clock, relay);

        service.markExpiredSessionsOffline();

        InOrder inOrder = inOrder(relay, registry);
        inOrder.verify(relay).closeAgentSessions(session);
        inOrder.verify(registry).remove(session);
    }

    /** 验证未过期会话不触发终端中继收口。 */
    @Test
    void shouldNotCloseRelaySessionsBeforeHeartbeatTimeout() {
        MutableClock clock = new MutableClock(HEARTBEAT_AT.plusSeconds(89));
        ServerMapper serverMapper = mock(ServerMapper.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        TerminalRelayLifecycleService relay = mock(TerminalRelayLifecycleService.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("heartbeat-early-session");
        AgentWebSocketSession session = new AgentWebSocketSession(socket, clock);
        session.authenticate(SERVER_ID, LocalDateTime.ofInstant(HEARTBEAT_AT, ZoneOffset.UTC));
        when(registry.sessions()).thenReturn(List.of(session));
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, null, clock, relay);

        service.markExpiredSessionsOffline();

        verify(relay, never()).closeAgentSessions(any());
        verify(registry, never()).remove(session);
    }

    /** 验证当前连接断开时仅在离线 CAS 成功后发布离线状态。 */
    @Test
    void shouldPublishOfflineStatusAfterCurrentSessionDisconnects() {
        Clock clock = Clock.fixed(HEARTBEAT_AT, ZoneOffset.UTC);
        ServerMapper serverMapper = mock(ServerMapper.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        MonitorServerStatusPublisher statusPublisher = mock(MonitorServerStatusPublisher.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("disconnect-session");
        AgentWebSocketSession session = new AgentWebSocketSession(socket, clock);
        LocalDateTime heartbeatAt = LocalDateTime.ofInstant(HEARTBEAT_AT, ZoneOffset.UTC).withNano(123_456_000);
        session.authenticate(SERVER_ID, heartbeatAt);
        session.heartbeat(heartbeatAt);
        when(serverMapper.markAgentOffline(SERVER_ID, heartbeatAt)).thenReturn(1);
        TerminalRelayLifecycleService relay = mock(TerminalRelayLifecycleService.class);
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, statusPublisher, clock, relay);

        service.markOfflineOnDisconnect(session);

        verify(serverMapper).markAgentOffline(SERVER_ID, heartbeatAt);
        verify(statusPublisher).publish(SERVER_ID, "offline", "offline", heartbeatAt);
    }

    /** 验证心跳携带投递遥测时按 UTC 转换并落库。 */
    @Test
    void shouldPersistDeliveryStatsFromHeartbeatPayload() {
        Clock clock = Clock.fixed(HEARTBEAT_AT, ZoneOffset.UTC);
        ServerMapper serverMapper = mock(ServerMapper.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        LocalDateTime heartbeatAt = LocalDateTime.ofInstant(HEARTBEAT_AT, ZoneOffset.UTC);
        when(serverMapper.markAgentOnlineAndHeartbeat(SERVER_ID, heartbeatAt)).thenReturn(1);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("stats-session");
        AgentWebSocketSession session = new AgentWebSocketSession(socket, clock);
        session.authenticate(SERVER_ID, heartbeatAt);
        TerminalRelayLifecycleService relay = mock(TerminalRelayLifecycleService.class);
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, null, clock, relay);

        service.heartbeat(session, new AgentHeartbeatPayload(3L, 456L,
                OffsetDateTime.parse("2026-08-03T00:00:00Z"), 2L, 1L, 789L));

        verify(serverMapper).updateDeliveryStats(SERVER_ID,
                LocalDateTime.ofInstant(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                3L, 456L, 2L, 1L, 789L);
    }

    /** 验证心跳未携带投递遥测时不触发统计更新。 */
    @Test
    void shouldSkipDeliveryStatsUpdateWhenPayloadHasNone() {
        Clock clock = Clock.fixed(HEARTBEAT_AT, ZoneOffset.UTC);
        ServerMapper serverMapper = mock(ServerMapper.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        LocalDateTime heartbeatAt = LocalDateTime.ofInstant(HEARTBEAT_AT, ZoneOffset.UTC);
        when(serverMapper.markAgentOnlineAndHeartbeat(SERVER_ID, heartbeatAt)).thenReturn(1);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("stats-empty-session");
        AgentWebSocketSession session = new AgentWebSocketSession(socket, clock);
        session.authenticate(SERVER_ID, heartbeatAt);
        TerminalRelayLifecycleService relay = mock(TerminalRelayLifecycleService.class);
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, null, clock, relay);

        service.heartbeat(session, new AgentHeartbeatPayload(null, null, null, null, null, null));

        verify(serverMapper, never()).updateDeliveryStats(any(), any(), any(), any(), any(), any(), any());
    }

    /** 提供测试可推进的 UTC Clock。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

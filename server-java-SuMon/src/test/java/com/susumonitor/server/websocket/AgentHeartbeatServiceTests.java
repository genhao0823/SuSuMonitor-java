package com.susumonitor.server.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.server.mapper.ServerMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
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
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("heartbeat-session");
        when(socket.isOpen()).thenReturn(true);
        AgentWebSocketSession session = new AgentWebSocketSession(socket, clock);
        session.authenticate(SERVER_ID, LocalDateTime.ofInstant(HEARTBEAT_AT, ZoneOffset.UTC));
        when(registry.sessions()).thenReturn(List.of(session));
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, clock);

        service.markExpiredSessionsOffline();
        verify(serverMapper, never()).markAgentOffline(SERVER_ID, session.lastHeartbeatAt());

        clock.set(HEARTBEAT_AT.plusSeconds(91));
        service.markExpiredSessionsOffline();
        verify(serverMapper).markAgentOffline(SERVER_ID, session.lastHeartbeatAt());
        verify(registry).remove(session);
        verify(socket).close(CloseStatus.SESSION_NOT_RELIABLE);
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
        AgentHeartbeatService service = new AgentHeartbeatServiceImpl(serverMapper, registry, statusPublisher, clock);

        service.markOfflineOnDisconnect(session);

        verify(serverMapper).markAgentOffline(SERVER_ID, heartbeatAt);
        verify(statusPublisher).publish(SERVER_ID, "offline", "offline", heartbeatAt);
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

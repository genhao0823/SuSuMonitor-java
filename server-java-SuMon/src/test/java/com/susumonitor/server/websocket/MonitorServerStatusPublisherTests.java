package com.susumonitor.server.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 验证 Agent 状态转换仅向对应服务器的 Monitor 订阅者广播。 */
class MonitorServerStatusPublisherTests {

    /** 验证离线状态帧包含服务状态、Agent 状态和最后心跳时间。 */
    @Test
    void shouldPublishStatusTransitionToSubscribedMonitorSessions() throws Exception {
        Long serverId = 1001L;
        MonitorSubscriptionRegistry registry = mock(MonitorSubscriptionRegistry.class);
        MonitorSessionTerminationService terminationService = mock(MonitorSessionTerminationService.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        MonitorWebSocketSession subscriber = new MonitorWebSocketSession(socket, null);
        when(registry.subscribers(serverId)).thenReturn(List.of(subscriber));
        Clock clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC);
        MonitorServerStatusPublisher publisher = new MonitorServerStatusPublisher(
                new ObjectMapper().findAndRegisterModules(), registry, clock, terminationService);
        LocalDateTime heartbeatAt = LocalDateTime.of(2026, 8, 1, 11, 59, 30, 123_456_000);

        publisher.publish(serverId, "offline", "offline", heartbeatAt);

        verify(socket).sendMessage(any(TextMessage.class));
    }
}

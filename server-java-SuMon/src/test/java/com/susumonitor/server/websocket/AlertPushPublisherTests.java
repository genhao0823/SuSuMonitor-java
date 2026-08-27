package com.susumonitor.server.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.alert.service.AlertResolvedEvent;
import com.susumonitor.server.module.alert.service.AlertTriggeredEvent;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 验证告警广播经 Monitor 会话统一出站路径发送。 */
class AlertPushPublisherTests {

    /** 订阅者应收到通过 MonitorWebSocketSession 发送的 alert.push 帧。 */
    @Test
    void alertShouldSendThroughMonitorSession() throws Exception {
        MonitorSubscriptionRegistry registry = mock(MonitorSubscriptionRegistry.class);
        MonitorSessionTerminationService terminationService = mock(MonitorSessionTerminationService.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        MonitorWebSocketSession monitor = new MonitorWebSocketSession(socket, null);
        when(registry.subscribers(1L)).thenReturn(List.of(monitor));
        AlertPushPublisher publisher = new AlertPushPublisher(new ObjectMapper(), registry, Clock.systemUTC(),
                terminationService);

        publisher.onAlertTriggered(new AlertTriggeredEvent(1L, null));

        verify(socket).sendMessage(any(TextMessage.class));
    }

    /** 恢复事件应推送同一 alert.push 帧，payload 中的告警记录为 resolved 状态。 */
    @Test
    void resolvedAlertShouldSendThroughMonitorSession() throws Exception {
        MonitorSubscriptionRegistry registry = mock(MonitorSubscriptionRegistry.class);
        MonitorSessionTerminationService terminationService = mock(MonitorSessionTerminationService.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        MonitorWebSocketSession monitor = new MonitorWebSocketSession(socket, null);
        when(registry.subscribers(1L)).thenReturn(List.of(monitor));
        AlertPushPublisher publisher = new AlertPushPublisher(new ObjectMapper(), registry, Clock.systemUTC(),
                terminationService);
        AlertRecordVo record = new AlertRecordVo();
        record.setId(7L);
        record.setServerId(1L);
        record.setStatus("resolved");

        publisher.onAlertResolved(new AlertResolvedEvent(1L, record));

        verify(socket).sendMessage(any(TextMessage.class));
    }
}

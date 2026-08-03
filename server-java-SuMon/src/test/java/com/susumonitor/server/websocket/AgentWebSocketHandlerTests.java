package com.susumonitor.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.server.entity.ServerEntity;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.scheduling.TaskScheduler;

/** 验证 Agent 首帧十秒超时边界、认证中会话保护、error 帧含 code 和 authenticated/ack payload 冻结。 */
class AgentWebSocketHandlerTests {

    private static final Instant CONNECTED_AT = Instant.parse("2026-07-22T00:00:00Z");

    /** 验证连接建立九秒时保留，十一秒时关闭。 */
    @Test
    void shouldCloseOnlyAfterAuthenticationTimeout() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-timeout");
        AgentWebSocketHandler handler = handler(clock);
        handler.afterConnectionEstablished(socket);

        clock.set(CONNECTED_AT.plusSeconds(9));
        handler.closeUnauthenticatedSessions();
        verify(socket, never()).close(any(CloseStatus.class));

        clock.set(CONNECTED_AT.plusSeconds(11));
        handler.closeUnauthenticatedSessions();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    /** 验证认证中的慢连接超过十秒仍不会被扫描误杀。 */
    @Test
    void shouldNotCloseAuthenticatingSession() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-authenticating");
        AgentAuthenticationService authenticationService = mock(AgentAuthenticationService.class);
        AgentHeartbeatService heartbeatService = mock(AgentHeartbeatService.class);
        CountDownLatch authenticationStarted = new CountDownLatch(1);
        CountDownLatch releaseAuthentication = new CountDownLatch(1);
        ServerEntity server = new ServerEntity();
        server.setId(1001L);
        when(authenticationService.authenticate(1001L, "test-token")).thenAnswer(invocation -> {
            authenticationStarted.countDown();
            if (!releaseAuthentication.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("authentication test timed out");
            }
            return server;
        });
        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper(), authenticationService,
                heartbeatService, mock(AgentConnectionRegistry.class), mock(MetricsService.class), clock);
        handler.afterConnectionEstablished(socket);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> authentication = executor.submit(() -> {
                try {
                    handler.handleTextMessage(socket, new org.springframework.web.socket.TextMessage("""
                            {"type":"agent.authenticate","payload":{"server_id":1001,"token":"test-token"}}
                            """));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            org.junit.jupiter.api.Assertions.assertTrue(authenticationStarted.await(5, TimeUnit.SECONDS));

            clock.set(CONNECTED_AT.plusSeconds(11));
            handler.closeUnauthenticatedSessions();
            verify(socket, never()).close(any(CloseStatus.class));

            releaseAuthentication.countDown();
            authentication.get(5, TimeUnit.SECONDS);
        } finally {
            releaseAuthentication.countDown();
            executor.shutdownNow();
        }
    }

    /** 验证 error 帧包含 code 和 message 字段。 */
    @Test
    void errorFrameShouldContainCodeAndMessage() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-error");
        AgentWebSocketHandler handler = handler(clock);
        handler.afterConnectionEstablished(socket);

        // 发送无效状态消息（未认证时发 heartbeat），触发 error 帧。
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"heartbeat\",\"messageId\":\"err-1\",\"payload\":{}}"));

        // 验证 error 帧包含 code 字段。
        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(captor.capture());
        JsonNode payload = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("error", payload.get("type").asText());
        assertTrue(payload.get("payload").has("code"));
        assertTrue(payload.get("payload").has("message"));
    }

    /** 验证 agent.authenticated 响应包含 server_id 和 authenticated_at。 */
    @Test
    void authenticatedPayloadShouldContainServerIdAndTimestamp() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-auth-payload");
        AgentAuthenticationService authenticationService = mock(AgentAuthenticationService.class);
        AgentHeartbeatService heartbeatService = mock(AgentHeartbeatService.class);
        ServerEntity server = new ServerEntity();
        server.setId(2002L);
        when(authenticationService.authenticate(2002L, "test-token")).thenReturn(server);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper(),
                authenticationService, heartbeatService, mock(AgentConnectionRegistry.class),
                mock(MetricsService.class), clock);
        handler.afterConnectionEstablished(socket);

        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"agent.authenticate\",\"payload\":{\"server_id\":2002,\"token\":\"test-token\"}}"));

        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(captor.capture());
        JsonNode payload = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("agent.authenticated", payload.get("type").asText());
        assertEquals(2002, payload.get("payload").get("server_id").asInt());
        assertTrue(payload.get("payload").has("authenticated_at"));
    }

    /** 验证 heartbeat.ack 响应包含 server_id 和 last_heartbeat_at。 */
    @Test
    void heartbeatAckPayloadShouldContainServerIdAndTimestamp() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-heartbeat-ack");
        AgentAuthenticationService authenticationService = mock(AgentAuthenticationService.class);
        AgentHeartbeatService heartbeatService = mock(AgentHeartbeatService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        when(registry.replace(any())).thenReturn(java.util.Optional.empty());
        ServerEntity server = new ServerEntity();
        server.setId(3003L);
        when(authenticationService.authenticate(3003L, "test-token")).thenReturn(server);

        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper(),
                authenticationService, heartbeatService, registry,
                mock(MetricsService.class), clock);
        handler.afterConnectionEstablished(socket);

        // 先认证。
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"agent.authenticate\",\"payload\":{\"server_id\":3003,\"token\":\"test-token\"}}"));

        // 清除之前的消息捕获，发送心跳。
        org.mockito.Mockito.clearInvocations(socket);
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"heartbeat\",\"message_id\":\"hb-1\",\"payload\":{}}"));

        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(captor.capture());
        JsonNode payload = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("heartbeat.ack", payload.get("type").asText());
        assertEquals(3003, payload.get("payload").get("server_id").asInt());
        assertTrue(payload.get("payload").has("last_heartbeat_at"));
    }

    /** 验证 metrics.report 缺少 UUID message_id 时被拒绝，且不会进入 Metrics 写入服务。 */
    @Test
    void metricsReportWithoutMessageIdShouldBeRejected() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-metrics-message-id");
        AgentAuthenticationService authenticationService = mock(AgentAuthenticationService.class);
        AgentHeartbeatService heartbeatService = mock(AgentHeartbeatService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        MetricsService metricsService = mock(MetricsService.class);
        ServerEntity server = new ServerEntity();
        server.setId(4004L);
        when(authenticationService.authenticate(4004L, "test-token")).thenReturn(server);
        when(registry.replace(any())).thenReturn(java.util.Optional.empty());
        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper().findAndRegisterModules(), authenticationService,
                heartbeatService, registry, metricsService, clock);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"agent.authenticate\",\"payload\":{\"server_id\":4004,\"token\":\"test-token\"}}"));
        org.mockito.Mockito.clearInvocations(socket);

        handler.handleTextMessage(socket, new TextMessage("""
                {"type":"metrics.report","payload":{"server_id":4004,"collected_at":"2026-07-22T00:00:00Z","cpu_percent":10}}
                """));

        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(captor.capture());
        JsonNode response = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("error", response.get("type").asText());
        assertEquals(40002, response.get("payload").get("code").asInt());
        verifyNoInteractions(metricsService);
    }

    /** 验证合法 metrics.report 原样传递 UUID 幂等键和载荷至 Metrics 服务。 */
    @Test
    void metricsReportShouldPassMessageIdToMetricsService() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-metrics-report");
        AgentAuthenticationService authenticationService = mock(AgentAuthenticationService.class);
        AgentHeartbeatService heartbeatService = mock(AgentHeartbeatService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        MetricsService metricsService = mock(MetricsService.class);
        ServerEntity server = new ServerEntity();
        server.setId(5005L);
        when(authenticationService.authenticate(5005L, "test-token")).thenReturn(server);
        when(registry.replace(any())).thenReturn(java.util.Optional.empty());
        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper().findAndRegisterModules(), authenticationService,
                heartbeatService, registry, metricsService, clock);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"agent.authenticate\",\"payload\":{\"server_id\":5005,\"token\":\"test-token\"}}"));
        String messageId = "77b5244a-51ed-4c64-9ba8-7f54c68d0b08";

        handler.handleTextMessage(socket, new TextMessage("""
                {"type":"metrics.report","message_id":"77b5244a-51ed-4c64-9ba8-7f54c68d0b08","payload":{"server_id":5005,"collected_at":"2026-07-22T00:00:00Z","cpu_percent":10}}
                """));

        verify(metricsService).report(eq(5005L), eq(messageId), any());
    }

    /** 验证指标事务成功后返回关联请求 ID 的 metrics.ack，不承诺异步告警已完成。 */
    @Test
    void metricsReportShouldAcknowledgeCommittedIngress() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-metrics-ack");
        AgentAuthenticationService authenticationService = mock(AgentAuthenticationService.class);
        AgentHeartbeatService heartbeatService = mock(AgentHeartbeatService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        MetricsService metricsService = mock(MetricsService.class);
        ServerEntity server = new ServerEntity();
        server.setId(6006L);
        when(authenticationService.authenticate(6006L, "test-token")).thenReturn(server);
        when(registry.replace(any())).thenReturn(java.util.Optional.empty());
        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper().findAndRegisterModules(),
                authenticationService, heartbeatService, registry, metricsService, clock);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"agent.authenticate\",\"payload\":{\"server_id\":6006,\"token\":\"test-token\"}}"));
        org.mockito.Mockito.clearInvocations(socket);
        String messageId = "2f9678a1-f5f0-4c6c-b9b0-2f9556b2f543";

        handler.handleTextMessage(socket, new TextMessage("""
                {"type":"metrics.report","message_id":"2f9678a1-f5f0-4c6c-b9b0-2f9556b2f543","payload":{"server_id":6006,"collected_at":"2026-07-22T00:00:00Z","cpu_percent":10}}
                """));

        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(captor.capture());
        JsonNode response = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("metrics.ack", response.get("type").asText());
        assertEquals(messageId, response.get("message_id").asText());
        assertEquals(6006L, response.get("payload").get("server_id").asLong());
        assertEquals("2026-07-22T00:00Z", response.get("payload").get("collected_at").asText());
        verify(metricsService).report(eq(6006L), eq(messageId), any());
    }

    /** 验证指标事务失败时只发送既有 error 帧，绝不确认尚未接受的指标。 */
    @Test
    void metricsReportShouldNotAcknowledgeFailedIngress() throws Exception {
        MutableClock clock = new MutableClock(CONNECTED_AT);
        WebSocketSession socket = socket("agent-metrics-error");
        AgentAuthenticationService authenticationService = mock(AgentAuthenticationService.class);
        AgentHeartbeatService heartbeatService = mock(AgentHeartbeatService.class);
        AgentConnectionRegistry registry = mock(AgentConnectionRegistry.class);
        MetricsService metricsService = mock(MetricsService.class);
        ServerEntity server = new ServerEntity();
        server.setId(7007L);
        when(authenticationService.authenticate(7007L, "test-token")).thenReturn(server);
        when(registry.replace(any())).thenReturn(java.util.Optional.empty());
        doThrow(new com.susumonitor.server.common.BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER))
                .when(metricsService).report(eq(7007L), any(), any());
        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper().findAndRegisterModules(),
                authenticationService, heartbeatService, registry, metricsService, clock);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"agent.authenticate\",\"payload\":{\"server_id\":7007,\"token\":\"test-token\"}}"));
        org.mockito.Mockito.clearInvocations(socket);

        handler.handleTextMessage(socket, new TextMessage("""
                {"type":"metrics.report","message_id":"ad19a0dc-3ae2-46d7-8410-85ef50257eaf","payload":{"server_id":7007,"collected_at":"2026-07-22T00:00:00Z","cpu_percent":10}}
                """));

        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(captor.capture());
        JsonNode response = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("error", response.get("type").asText());
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER.getCode(), response.get("payload").get("code").asInt());
    }

    /** 验证已建立连接因总连接配额耗尽时返回 42901 并以策略违规关闭。 */
    @Test
    void connectionLimitShouldSendErrorAndClose() throws Exception {
        WebSocketSession admittedSocket = socket("agent-admitted");
        WebSocketSession rejectedSocket = socket("agent-rejected");
        AppProperties properties = new AppProperties();
        properties.getAgent().setMaxConnections(1);
        properties.getAgent().setMaxUnauthenticatedConnections(1);
        AgentWebSocketHandler handler = new AgentWebSocketHandler(new ObjectMapper(),
                mock(AgentAuthenticationService.class), mock(AgentHeartbeatService.class),
                mock(AgentConnectionRegistry.class), mock(MetricsService.class), Clock.systemUTC(),
                new AgentConnectionLimiter(properties), new AgentMessageRateLimiter(properties, Clock.systemUTC()), null);

        handler.afterConnectionEstablished(admittedSocket);
        handler.afterConnectionEstablished(rejectedSocket);

        org.mockito.ArgumentCaptor<TextMessage> captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(rejectedSocket).sendMessage(captor.capture());
        JsonNode response = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals(ErrorCode.AGENT_CONNECTION_LIMIT_REACHED.getCode(), response.get("payload").get("code").asInt());
        verify(rejectedSocket).close(CloseStatus.POLICY_VIOLATION);
    }

    private AgentWebSocketHandler handler(Clock clock) {
        return new AgentWebSocketHandler(new ObjectMapper(), mock(AgentAuthenticationService.class),
                mock(AgentHeartbeatService.class), mock(AgentConnectionRegistry.class),
                mock(MetricsService.class), clock);
    }

    private WebSocketSession socket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        return socket;
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

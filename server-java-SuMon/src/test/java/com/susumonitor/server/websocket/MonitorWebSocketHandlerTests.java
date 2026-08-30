package com.susumonitor.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.server.entity.ServerEntity;
import com.susumonitor.server.module.server.mapper.ServerMapper;
import com.susumonitor.server.security.AuthenticatedUser;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 验证 Monitor WebSocket 的 error 帧包含 code，非法订阅返回 error 帧而非静默断连。
 */
class MonitorWebSocketHandlerTests {

    private static final Instant CONNECTED_AT = Instant.parse("2026-07-22T00:00:00Z");

    /** 验证非法 server_id 发 error 帧含 code=40002 后 close BAD_DATA。 */
    @Test
    void invalidServerIdShouldReturnErrorFrameAndClose() throws Exception {
        TestContext ctx = new TestContext();
        ctx.sendText("{\"type\":\"metrics.subscribe\",\"message_id\":\"s1\",\"payload\":{\"server_id\":-1}}");

        JsonNode payload = ctx.captureErrorFrame();
        assertEquals("error", payload.get("type").asText());
        assertEquals("s1", payload.get("message_id").asText());
        assertEquals(40002, payload.get("payload").get("code").asInt());
        verify(ctx.socket).close(CloseStatus.BAD_DATA);
    }

    /** 验证不存在的服务器发 error 帧含 code=40400，保留连接。 */
    @Test
    void nonexistentServerShouldReturnErrorFrameWithoutClosing() throws Exception {
        TestContext ctx = new TestContext();
        when(ctx.serverMapper.selectActiveServerById(99999L)).thenReturn(null);

        ctx.sendText("{\"type\":\"metrics.subscribe\",\"message_id\":\"s2\",\"payload\":{\"server_id\":99999}}");

        JsonNode payload = ctx.captureErrorFrame();
        assertEquals(40400, payload.get("payload").get("code").asInt());
        verify(ctx.socket, never()).close(any(CloseStatus.class));
    }

    /** 验证未知消息类型发 error 帧含 code=40000，保留连接。 */
    @Test
    void unknownTypeShouldReturnErrorFrameWithoutClosing() throws Exception {
        TestContext ctx = new TestContext();

        ctx.sendText("{\"type\":\"unknown.type\",\"message_id\":\"s3\",\"payload\":{\"server_id\":1}}");

        JsonNode payload = ctx.captureErrorFrame();
        assertEquals(40000, payload.get("payload").get("code").asInt());
        verify(ctx.socket, never()).close(any(CloseStatus.class));
    }

    /** 验证非法 JSON 发 error 帧含 code=40000，保留连接。 */
    @Test
    void invalidJsonShouldReturnErrorFrameWithoutClosing() throws Exception {
        TestContext ctx = new TestContext();

        ctx.sendText("not-json-at-all");

        JsonNode payload = ctx.captureErrorFrame();
        assertEquals(40000, payload.get("payload").get("code").asInt());
        verify(ctx.socket, never()).close(any(CloseStatus.class));
    }

    /** 验证合法订阅不发送 error 帧。 */
    @Test
    void validSubscribeShouldNotSendError() throws Exception {
        TestContext ctx = new TestContext();
        ctx.sendText("{\"type\":\"metrics.subscribe\",\"message_id\":\"ok\",\"payload\":{\"server_id\":1}}");

        verify(ctx.socket, never()).sendMessage(any(TextMessage.class));
    }

    /** 验证超过 terminal.open 突发额度时回送稳定的 42904 错误帧且不调用中继。 */
    @Test
    void terminalMessageLimitShouldReturnErrorFrameWithoutRelaying() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getTerminal().setOpenBurst(1);
        TerminalMessageRateLimiter limiter = new TerminalMessageRateLimiter(properties, Clock.systemUTC());
        TerminalMonitorRelayService relayService = mock(TerminalMonitorRelayService.class);
        TestContext ctx = new TestContext(relayService, limiter);
        String payload = "{\"type\":\"terminal.open\",\"message_id\":\""
                + java.util.UUID.randomUUID() + "\",\"timestamp\":\"2026-07-26T00:00:00Z\",\"payload\":{\"server_id\":1,\"cols\":80,\"rows\":24}}";

        ctx.sendText(payload);
        ctx.sendText(payload.replaceFirst("terminal.open", "terminal.open"));

        JsonNode error = ctx.captureErrorFrame();
        assertEquals(42904, error.get("payload").get("code").asInt());
        verify(relayService).relay(any(MonitorWebSocketSession.class), any(TerminalMessage.class));
    }

    /** 验证普通用户发送 terminal.open 回 40302 且不调用中继（终端通道仅 admin）。 */
    @Test
    void nonAdminTerminalOpenShouldReturnForbiddenWithoutRelaying() throws Exception {
        TerminalMessageRateLimiter limiter = new TerminalMessageRateLimiter(new AppProperties(), Clock.systemUTC());
        TerminalMonitorRelayService relayService = mock(TerminalMonitorRelayService.class);
        AuthenticatedUser ordinaryUser =
                new AuthenticatedUser(2L, "alice", "user", "approved", null, OffsetDateTime.now());
        TestContext ctx = new TestContext(relayService, limiter, ordinaryUser);
        String payload = "{\"type\":\"terminal.open\",\"message_id\":\""
                + java.util.UUID.randomUUID() + "\",\"timestamp\":\"2026-07-26T00:00:00Z\",\"payload\":{\"server_id\":1,\"cols\":80,\"rows\":24}}";

        ctx.sendText(payload);

        JsonNode error = ctx.captureErrorFrame();
        assertEquals(40302, error.get("payload").get("code").asInt());
        verify(relayService, never()).relay(any(MonitorWebSocketSession.class), any(TerminalMessage.class));
    }

    /** 验证普通用户仍可订阅指标（指标订阅授权策略不因终端收紧而改变）。 */
    @Test
    void nonAdminMetricsSubscribeShouldSucceed() throws Exception {
        AuthenticatedUser ordinaryUser =
                new AuthenticatedUser(2L, "alice", "user", "approved", null, OffsetDateTime.now());
        TestContext ctx = new TestContext(null, null, ordinaryUser);

        ctx.sendText("{\"type\":\"metrics.subscribe\",\"message_id\":\"ok\",\"payload\":{\"server_id\":1}}");

        verify(ctx.socket, never()).sendMessage(any(TextMessage.class));
    }

    /** 封装 MonitorWebSocketHandler 的测试上下文。 */
    private static final class TestContext {
        final WebSocketSession socket;
        final ServerMapper serverMapper;
        final MonitorWebSocketHandler handler;
        private TextMessage lastMessage;

        TestContext() {
            this(null, null, defaultUser());
        }

        TestContext(TerminalMonitorRelayService terminalRelayService, TerminalMessageRateLimiter terminalMessageRateLimiter) {
            this(terminalRelayService, terminalMessageRateLimiter, defaultUser());
        }

        TestContext(TerminalMonitorRelayService terminalRelayService,
                TerminalMessageRateLimiter terminalMessageRateLimiter, AuthenticatedUser user) {
            this.socket = mock(WebSocketSession.class);
            when(socket.getId()).thenReturn("monitor-test");
            when(socket.isOpen()).thenReturn(true);
            when(socket.getAttributes()).thenReturn(buildAttributes(user));

            this.serverMapper = mock(ServerMapper.class);
            ServerEntity server = new ServerEntity();
            server.setId(1L);
            when(serverMapper.selectActiveServerById(1L)).thenReturn(server);

            MonitorSubscriptionRegistry registry = new MonitorSubscriptionRegistry();
            Clock clock = Clock.fixed(CONNECTED_AT, ZoneOffset.UTC);
            this.handler = terminalRelayService == null
                    ? new MonitorWebSocketHandler(new ObjectMapper(), serverMapper, registry, clock)
                    : new MonitorWebSocketHandler(new ObjectMapper(), serverMapper, registry, clock,
                            terminalRelayService, null, terminalMessageRateLimiter);

            // 捕获 sendMessage 的参数供测试验证。
            try {
                handler.afterConnectionEstablished(socket);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            // 在 afterConnectionEstablished 之后注册捕获器，避免捕获握手阶段的 close 调用。
            try {
                org.mockito.Mockito.doAnswer(invocation -> {
                    lastMessage = invocation.getArgument(0);
                    return null;
                }).when(socket).sendMessage(any(TextMessage.class));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static AuthenticatedUser defaultUser() {
            return new AuthenticatedUser(1L, "admin", "admin", "approved", null, OffsetDateTime.now());
        }

        private Map<String, Object> buildAttributes(AuthenticatedUser user) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("authenticated_user", user);
            return attrs;
        }

        void sendText(String payload) {
            try {
                handler.handleTextMessage(socket, new TextMessage(payload));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        JsonNode captureErrorFrame() throws Exception {
            assertTrue(lastMessage != null, "expected an error frame but none was sent");
            return new ObjectMapper().readTree(lastMessage.getPayload());
        }
    }
}

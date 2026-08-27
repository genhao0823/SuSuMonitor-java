package com.susumonitor.api

import com.susumonitor.data.WsMessage
import com.susumonitor.data.model.TerminalClosedPayload
import com.susumonitor.data.model.TerminalDataPayload
import com.susumonitor.data.model.TerminalErrorPayload
import com.susumonitor.data.model.TerminalOpenedPayload
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TerminalClient 状态机测试：开/关/输出/异常关闭与断线自动重连。
 *
 * 用 MockK 注入假 WsClient（messages/connectionState 为可控 Flow）。
 * messages 用 replay 缓冲：collect 订阅与 emit 之间无竞态（早发射的消息会重放）。
 * 断言统一走轮询（虚拟时间忙等，真实时间上限兜底），等待 IO 线程处理。
 */
class TerminalClientTest {

    private lateinit var wsClient: WsClient
    private lateinit var messages: MutableSharedFlow<WsMessage>
    private lateinit var connectionState: MutableStateFlow<ConnectionState>
    private lateinit var client: TerminalClient

    @Before
    fun setUp() {
        wsClient = mockk()
        messages = MutableSharedFlow(replay = 64, extraBufferCapacity = 64)
        connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        every { wsClient.messages } returns messages
        every { wsClient.connectionState } returns connectionState
        every { wsClient.sendTerminalFrame(any(), any()) } returns true
        every { wsClient.ensureConnected(any()) } returns true
        client = TerminalClient(wsClient)
        // 退避参数调小：单次重试 10ms，上限 60ms
        client.reconnectInitialDelayMs = 10L
        client.reconnectMaxDelayMs = 60L
        client.startCollecting()
    }

    private suspend fun openAndAwaitOpened() {
        assertTrue(client.open(1L, 80, 24))
        messages.emit(WsMessage.TerminalOpened(TerminalOpenedPayload(1L, "sess-1", "/bin/bash")))
        awaitCondition("phase 应变为 OPEN") { client.state.phase == TerminalPhase.OPEN }
    }

    private suspend fun awaitPhase(expected: TerminalPhase) {
        awaitCondition("phase=${client.state.phase} 未变为 $expected") {
            client.state.phase == expected
        }
    }

    /** 虚拟时间轮询 + 真实时间上限兜底（collect 在 IO 线程按真实时间推进）。 */
    private suspend fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue(message, System.currentTimeMillis() < deadline)
            kotlinx.coroutines.delay(10)
        }
    }

    // ---- 基础状态机 ----

    @Test
    fun `open then opened transitions to OPEN`() = runTest {
        assertTrue(client.open(1L, 80, 24))
        assertEquals(TerminalPhase.AWAITING_OPEN, client.state.phase)
        messages.emit(WsMessage.TerminalOpened(TerminalOpenedPayload(1L, "sess-1", "/bin/bash")))
        awaitPhase(TerminalPhase.OPEN)
        assertEquals("sess-1", client.state.sessionId)
        assertEquals(0, client.state.reconnectAttempts)
    }

    @Test
    fun `input and resize only sent while OPEN`() = runTest {
        openAndAwaitOpened()
        client.sendInput("ls".toByteArray())
        client.resize(100, 30)
        // open + input + resize 共 3 帧
        verify(exactly = 3) { wsClient.sendTerminalFrame(any(), any()) }
    }

    @Test
    fun `close marks user intent and blocks auto reconnect`() = runTest {
        openAndAwaitOpened()
        client.close()
        assertEquals(TerminalPhase.CLOSING, client.state.phase)
        // WS 断开不应触发重连（用户主动关闭）
        connectionState.value = ConnectionState.DISCONNECTED
        kotlinx.coroutines.delay(300)
        assertEquals(TerminalPhase.CLOSING, client.state.phase)
        // open + close 共 2 帧，无自动重开
        verify(exactly = 2) { wsClient.sendTerminalFrame(any(), any()) }
    }

    // ---- 断线自动重连 ----

    @Test
    fun `ws disconnect enters RECONNECTING and reconnect reopens session`() = runTest {
        openAndAwaitOpened()
        connectionState.value = ConnectionState.DISCONNECTED
        awaitPhase(TerminalPhase.RECONNECTING)
        // 通道恢复 → 立即重开（复用最近尺寸）
        connectionState.value = ConnectionState.CONNECTED
        awaitPhase(TerminalPhase.AWAITING_OPEN)
        verify(exactly = 2) { wsClient.sendTerminalFrame(any(), any()) }
        messages.emit(WsMessage.TerminalOpened(TerminalOpenedPayload(1L, "sess-2", "/bin/bash")))
        awaitPhase(TerminalPhase.OPEN)
        assertEquals("sess-2", client.state.sessionId)
        assertEquals(0, client.state.reconnectAttempts)
    }

    @Test
    fun `terminal closed with abnormal reason auto reconnects`() = runTest {
        openAndAwaitOpened()
        messages.emit(WsMessage.TerminalClosed(TerminalClosedPayload(1L, "sess-1", "agent_disconnected", null)))
        awaitPhase(TerminalPhase.RECONNECTING)
        // 通道在线，退避后自动重发 open
        awaitPhase(TerminalPhase.AWAITING_OPEN)
        verify(exactly = 2) { wsClient.sendTerminalFrame(any(), any()) }
    }

    @Test
    fun `terminal closed with process_exited does not reconnect`() = runTest {
        openAndAwaitOpened()
        messages.emit(WsMessage.TerminalClosed(TerminalClosedPayload(1L, "sess-1", "process_exited", 0)))
        awaitPhase(TerminalPhase.CLOSED)
        kotlinx.coroutines.delay(300)
        verify(exactly = 1) { wsClient.sendTerminalFrame(any(), any()) }
        assertEquals(null, client.state.errorMessage)
    }

    @Test
    fun `reconnect attempts grow then reset after success`() = runTest {
        openAndAwaitOpened()
        // 第一次断开：重连尝试 +1
        connectionState.value = ConnectionState.DISCONNECTED
        awaitPhase(TerminalPhase.RECONNECTING)
        assertTrue(client.state.reconnectAttempts >= 1)
        connectionState.value = ConnectionState.CONNECTED
        awaitPhase(TerminalPhase.AWAITING_OPEN)
        messages.emit(WsMessage.TerminalOpened(TerminalOpenedPayload(1L, "sess-3", "/bin/bash")))
        awaitPhase(TerminalPhase.OPEN)
        assertEquals(0, client.state.reconnectAttempts)
    }

    // ---- 输出与错误 ----

    @Test
    fun `output delivered only while OPEN`() = runTest {
        var received: ByteArray? = null
        client.onOutput = { received = it }
        openAndAwaitOpened()
        messages.emit(WsMessage.TerminalOutput(TerminalDataPayload(1L, "sess-1", "aGVsbG8="))) // "hello"
        awaitCondition("应收到 PTY 输出") { received != null }
        assertEquals("hello", received?.let { String(it) })
    }

    @Test
    fun `terminal error keeps session and surfaces message`() = runTest {
        openAndAwaitOpened()
        messages.emit(WsMessage.TerminalError(TerminalErrorPayload(50003, "auth failed")))
        awaitCondition("应收到错误消息") { client.state.errorMessage != null }
        assertEquals(TerminalPhase.OPEN, client.state.phase)
        assertTrue(client.state.errorMessage!!.contains("50003"))
    }

    @Test
    fun `forceClosed stops reconnecting`() = runTest {
        openAndAwaitOpened()
        connectionState.value = ConnectionState.DISCONNECTED
        awaitPhase(TerminalPhase.RECONNECTING)
        client.forceClosed()
        assertEquals(TerminalPhase.CLOSED, client.state.phase)
        kotlinx.coroutines.delay(300)
        verify(exactly = 1) { wsClient.sendTerminalFrame(any(), any()) }
        assertFalse(client.state.phase == TerminalPhase.AWAITING_OPEN)
    }
}

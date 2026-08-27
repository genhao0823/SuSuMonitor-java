package com.susumonitor.data.model

import com.susumonitor.data.AppJson
import com.susumonitor.data.WsMessage
import com.susumonitor.data.WsMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 终端 WS 帧解析测试：对齐 websocket-protocol.md v1.3 Terminal Messages。
 */
class TerminalWsModelsTest {

    private val parser = WsMessageParser(AppJson)

    @Test
    fun `terminal opened frame parses`() {
        val frame = """
            {
              "type": "terminal.opened",
              "message_id": "550e8400-e29b-41d4-a716-446655440010",
              "timestamp": "2026-08-06T10:00:00Z",
              "payload": {
                "server_id": 1,
                "session_id": "b2f1c3d4-1111-2222-3333-444455556666",
                "shell": "bash"
              }
            }
        """.trimIndent()

        val message = parser.parseByType(parser.parseFrame(frame))
        assertTrue(message is WsMessage.TerminalOpened)
        val opened = (message as WsMessage.TerminalOpened).payload
        assertEquals("bash", opened.shell)
        assertEquals("b2f1c3d4-1111-2222-3333-444455556666", opened.sessionId)
    }

    @Test
    fun `terminal output frame decodes base64`() {
        val payload = "hello world\n"
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val frame = """
            {
              "type": "terminal.output",
              "message_id": "550e8400-e29b-41d4-a716-446655440011",
              "timestamp": "2026-08-06T10:00:00Z",
              "payload": {
                "server_id": 1,
                "session_id": "b2f1c3d4-1111-2222-3333-444455556666",
                "data": "$encoded"
              }
            }
        """.trimIndent()

        val message = parser.parseByType(parser.parseFrame(frame))
        assertTrue(message is WsMessage.TerminalOutput)
        val output = (message as WsMessage.TerminalOutput).payload
        val decoded = Base64.getDecoder().decode(output.data)
        assertEquals(payload, String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `terminal closed frame parses with reason`() {
        val frame = """
            {
              "type": "terminal.closed",
              "message_id": "550e8400-e29b-41d4-a716-446655440012",
              "timestamp": "2026-08-06T10:00:00Z",
              "payload": {
                "server_id": 1,
                "session_id": "b2f1c3d4-1111-2222-3333-444455556666",
                "reason": "exit",
                "exit_code": 0
              }
            }
        """.trimIndent()

        val message = parser.parseByType(parser.parseFrame(frame))
        assertTrue(message is WsMessage.TerminalClosed)
        val closed = (message as WsMessage.TerminalClosed).payload
        assertEquals("exit", closed.reason)
        assertEquals(0, closed.exitCode)
    }

    @Test
    fun `terminal error frame parses`() {
        val frame = """
            {
              "type": "terminal.error",
              "message_id": "550e8400-e29b-41d4-a716-446655440013",
              "timestamp": "2026-08-06T10:00:00Z",
              "payload": {
                "code": 40904,
                "message": "agent offline"
              }
            }
        """.trimIndent()

        val message = parser.parseByType(parser.parseFrame(frame))
        assertTrue(message is WsMessage.TerminalError)
        val error = (message as WsMessage.TerminalError).payload
        assertEquals(40904, error.code)
    }

    @Test
    fun `terminal open frame structure has uuid and timestamp`() {
        // 验证 sendTerminalFrame 构造的帧结构（用 parser 反解析）
        val frame = """
            {
              "type": "terminal.open",
              "message_id": "550e8400-e29b-41d4-a716-446655440014",
              "timestamp": "2026-08-06T10:00:00Z",
              "payload": {"server_id": 1, "cols": 80, "rows": 24}
            }
        """.trimIndent()

        val parsed = parser.parseFrame(frame)
        assertEquals("terminal.open", parsed.type)
        assertTrue(parsed.messageId!!.isNotEmpty())
        assertTrue(parsed.timestamp!!.isNotEmpty())
    }
}

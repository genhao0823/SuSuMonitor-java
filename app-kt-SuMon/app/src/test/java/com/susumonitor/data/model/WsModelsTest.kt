package com.susumonitor.data.model

import com.susumonitor.data.AppJson
import com.susumonitor.data.WsMessage
import com.susumonitor.data.WsMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSocket 帧解析测试：对齐 websocket-protocol.md v1.3。
 * 测试走真实解析路径 parseFrame → parseByType。
 */
class WsModelsTest {

    private val parser = WsMessageParser(AppJson)

    @Test
    fun `metrics update frame parses`() {
        val frame = """
            {
              "type": "metrics.update",
              "message_id": "550e8400-e29b-41d4-a716-446655440000",
              "timestamp": "2026-07-21T12:00:00Z",
              "payload": {
                "server_id": 1,
                "metrics": {"cpu_percent": 35.2, "collected_at": "2026-07-21T11:59:58Z"}
              }
            }
        """.trimIndent()

        val message = parser.parseByType(parser.parseFrame(frame))
        assertTrue(message is WsMessage.MetricsUpdate)
        val update = (message as WsMessage.MetricsUpdate).payload
        assertEquals(1L, update.serverId)
        assertEquals(35.2, update.metrics.cpuPercent!!, 0.001)
    }

    @Test
    fun `alert push frame parses`() {
        val frame = """
            {
              "type": "alert.push",
              "message_id": "550e8400-e29b-41d4-a716-446655440001",
              "timestamp": "2026-08-05T10:00:00Z",
              "payload": {
                "server_id": 1,
                "alert": {"id": 1, "metric": "cpu", "current_value": 90.5, "threshold_value": 80.0, "level": "warning", "status": "unread", "triggered_at": "2026-08-05T10:00:00Z"}
              }
            }
        """.trimIndent()

        val message = parser.parseByType(parser.parseFrame(frame))
        assertTrue(message is WsMessage.AlertPush)
        val push = (message as WsMessage.AlertPush).payload
        assertEquals(1L, push.serverId)
        assertEquals("cpu", push.alert.metric)
    }

    @Test
    fun `server status update frame parses`() {
        val frame = """
            {
              "type": "server.status.update",
              "message_id": "550e8400-e29b-41d4-a716-446655440002",
              "timestamp": "2026-08-01T12:00:00Z",
              "payload": {
                "server_id": 1,
                "status": "offline",
                "agent_status": "offline",
                "last_heartbeat_at": "2026-08-01T11:59:30.123456Z"
              }
            }
        """.trimIndent()

        val message = parser.parseByType(parser.parseFrame(frame))
        assertTrue(message is WsMessage.ServerStatusUpdate)
        val status = (message as WsMessage.ServerStatusUpdate).payload
        assertEquals(1L, status.serverId)
        assertEquals("offline", status.agentStatus)
    }

    @Test
    fun `error frame with null message id parses`() {
        val frame = """
            {
              "type": "error",
              "message_id": null,
              "timestamp": "2026-07-22T00:00:00Z",
              "payload": {"code": 40002, "message": "invalid request parameter"}
            }
        """.trimIndent()

        val parsed = parser.parseFrame(frame)
        assertNull(parsed.messageId)
        assertEquals("error", parsed.type)
        val message = parser.parseByType(parsed)
        assertTrue(message is WsMessage.Error)
        val error = (message as WsMessage.Error).payload
        assertEquals(40002, error.code)
    }

    @Test
    fun `unknown frame type is ignored`() {
        val frame = """{"type": "future.type", "payload": {}}""".trimIndent()
        val parsed = parser.parseFrame(frame)
        assertNull(parser.parseByType(parsed))
    }
}

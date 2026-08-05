package com.susumonitor.data.model

import com.susumonitor.data.AppJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 服务器/指标/告警 DTO 反序列化测试。
 */
class ServerModelsTest {

    @Test
    fun `server decodes with all fields`() {
        val json = """
            {
              "id": 1,
              "name": "cloud-host",
              "host": "82.156.245.102",
              "description": null,
              "status": "online",
              "ssh_host": "127.0.0.1",
              "ssh_port": 22,
              "ssh_user": "root",
              "ssh_auth_type": "private_key",
              "agent_id": "abc-123",
              "agent_status": "online",
              "last_heartbeat_at": "2026-08-05T10:00:00Z",
              "created_at": "2026-07-20T00:00:00Z",
              "updated_at": "2026-07-20T00:00:00Z"
            }
        """.trimIndent()

        val server: Server = AppJson.decodeFromString(json)
        assertEquals(1L, server.id)
        assertEquals("online", server.status)
        assertEquals("private_key", server.sshAuthType)
        assertEquals("abc-123", server.agentId)
        assertNull(server.description)
    }

    @Test
    fun `server status decodes with delivery telemetry`() {
        val json = """
            {
              "server_id": 1,
              "status": "online",
              "agent_status": "online",
              "last_heartbeat_at": "2026-08-05T10:00:00Z",
              "delivery_pending_count": 3,
              "delivery_pending_bytes": 1200,
              "delivery_oldest_collected_at": "2026-08-05T09:59:00Z",
              "delivery_drop_count": 0,
              "delivery_dead_letter_count": 1,
              "delivery_dead_letter_bytes": 400,
              "checked_at": "2026-08-05T10:00:05Z"
            }
        """.trimIndent()

        val status: ServerStatus = AppJson.decodeFromString(json)
        assertEquals(3L, status.deliveryPendingCount)
        assertEquals(1L, status.deliveryDeadLetterCount)
    }

    @Test
    fun `metrics decodes with null numeric fields`() {
        val json = """
            {
              "server_id": 1,
              "cpu_percent": 35.2,
              "memory_percent": null,
              "memory_used": null,
              "memory_total": null,
              "disk_percent": null,
              "disk_used": null,
              "disk_total": null,
              "net_rx": 1024.0,
              "net_tx": 2048.0,
              "temperature": null,
              "load_avg": 0.5,
              "collected_at": "2026-07-21T11:59:58Z"
            }
        """.trimIndent()

        val metrics: Metrics = AppJson.decodeFromString(json)
        assertEquals(35.2, metrics.cpuPercent!!, 0.001)
        assertNull(metrics.memoryPercent)
        assertEquals(1024.0, metrics.netRx!!, 0.001)
    }

    @Test
    fun `alert record decodes`() {
        val json = """
            {
              "id": 1,
              "rule_id": 2,
              "server_id": 1,
              "metric": "cpu",
              "current_value": 90.5,
              "threshold_value": 80.0,
              "level": "critical",
              "status": "unread",
              "message": "cpu > 80.0",
              "read_by": null,
              "read_at": null,
              "triggered_at": "2026-08-05T10:00:00Z",
              "notified_at": "2026-08-05T10:00:01Z",
              "notify_channels": "email",
              "created_at": "2026-08-05T10:00:00Z"
            }
        """.trimIndent()

        val record: AlertRecord = AppJson.decodeFromString(json)
        assertEquals("critical", record.level)
        assertEquals("unread", record.status)
        assertEquals("email", record.notifyChannels)
    }

    @Test
    fun `alert push payload decodes`() {
        val json = """
            {
              "server_id": 1,
              "alert": {
                "id": 1,
                "rule_id": 2,
                "metric": "cpu",
                "current_value": 90.5,
                "threshold_value": 80.0,
                "level": "warning",
                "status": "unread",
                "triggered_at": "2026-08-05T10:00:00Z"
              }
            }
        """.trimIndent()

        val payload: AlertPushPayload = AppJson.decodeFromString(json)
        assertEquals(1L, payload.serverId)
        assertEquals(90.5, payload.alert.currentValue, 0.001)
    }
}

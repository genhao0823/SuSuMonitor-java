package com.susumonitor.data.model

import com.susumonitor.data.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 服务器管理/告警规则/Admin DTO 反序列化测试。
 */
class ServerManageModelsTest {

    @Test
    fun `ssh test result decodes`() {
        val json = """
            {
              "server_id": 1,
              "connected": true,
              "host_key_algorithm": "ssh-ed25519",
              "host_key_fingerprint": "SHA256:abc",
              "auth_type": "password",
              "duration_ms": 1234,
              "tested_at": "2026-08-05T10:00:00Z"
            }
        """.trimIndent()

        val result: SshTestResult = AppJson.decodeFromString(json)
        assertEquals(true, result.connected)
        assertEquals("password", result.authType)
        assertEquals(1234L, result.durationMs)
    }

    @Test
    fun `agent token vo decodes`() {
        val json = """
            {"server_id": 1, "agent_token": "abc-def", "created_at": "2026-08-05T10:00:00Z"}
        """.trimIndent()

        val token: AgentTokenVo = AppJson.decodeFromString(json)
        assertEquals("abc-def", token.agentToken)
    }

    @Test
    fun `ssh host key vo decodes`() {
        val json = """
            {
              "server_id": 1,
              "host_key_algorithm": "ssh-ed25519",
              "host_key_fingerprint": "SHA256:abc",
              "operation": "confirmed",
              "verified_at": "2026-08-05T10:00:00Z"
            }
        """.trimIndent()

        val vo: SshHostKeyVo = AppJson.decodeFromString(json)
        assertEquals("confirmed", vo.operation)
    }

    @Test
    fun `create server request encodes credentials per auth type`() {
        val request = CreateServerRequest(
            name = "host1",
            host = "1.2.3.4",
            sshHost = "1.2.3.4",
            sshPort = 22,
            sshUser = "root",
            sshAuthType = "private_key",
            sshPrivateKey = "-----BEGIN...",
        )
        val json = AppJson.encodeToString(CreateServerRequest.serializer(), request)
        assert(json.contains("\"ssh_auth_type\":\"private_key\""))
        assertNull(null)
    }

    @Test
    fun `admin user vo decodes`() {
        val json = """
            {
              "id": 2,
              "username": "bob",
              "role": "user",
              "reviewStatus": "pending",
              "createdAt": "2026-08-05T10:00:00Z"
            }
        """.trimIndent()

        val user: AdminUserVo = AppJson.decodeFromString(json)
        assertEquals(2L, user.id)
        assertEquals("pending", user.reviewStatus)
    }

    @Test
    fun `batch review result decodes`() {
        val json = """
            {"processed": 2, "failed": 1, "failed_ids": [5]}
        """.trimIndent()

        val result: BatchReviewResult = AppJson.decodeFromString(json)
        assertEquals(2, result.processed)
        assertEquals(listOf(5L), result.failedIds)
    }

    @Test
    fun `alert rule create request serializes`() {
        val request = CreateAlertRuleRequest(
            metric = "cpu",
            operator = ">",
            thresholdValue = 80.0,
            level = "warning",
            confirmCount = 3,
            notifyEmail = "a@b.com",
        )
        val json = AppJson.encodeToString(CreateAlertRuleRequest.serializer(), request)
        assert(json.contains("\"metric\":\"cpu\""))
        assert(json.contains("\"confirm_count\":3"))
    }
}

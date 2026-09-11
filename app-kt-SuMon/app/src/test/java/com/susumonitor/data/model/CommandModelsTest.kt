package com.susumonitor.data.model

import com.susumonitor.data.AppJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令域 DTO 反序列化测试：JSON 样本取自后端 `CommandRunVo` / openapi-command 契约。
 */
class CommandModelsTest {

    @Test
    fun `command run with result decodes`() {
        val json = """
            {
              "id": 7,
              "execution_id": "b0e5c9a0-1e2f-4a3b-9c8d-112233445566",
              "server_id": 3,
              "template_id": "disk_top_usage",
              "params": {"path": "/var/log"},
              "rendered_command": "du -h --max-depth=1 /var/log | sort -rh | head -20",
              "status": "succeeded",
              "source": "ai",
              "proposal": {"reason": "排查磁盘占用", "model": "approved-model", "prompt_version": "v1"},
              "result": {"stdout": "1.2G\t/var/log", "stderr": "", "truncated": true, "error": null},
              "exit_code": 0,
              "proposer_id": 1,
              "approver_id": 1,
              "expires_at": "2026-09-05T09:00:00Z",
              "created_at": "2026-09-05T08:50:00Z",
              "completed_at": "2026-09-05T08:51:00Z"
            }
        """.trimIndent()

        val run: CommandRun = AppJson.decodeFromString(json)
        assertEquals(7L, run.id)
        assertEquals("succeeded", run.status)
        assertEquals("ai", run.source)
        assertEquals("/var/log", run.params["path"])
        assertEquals("排查磁盘占用", run.proposal?.reason)
        assertEquals("1.2G\t/var/log", run.result?.stdout)
        assertEquals(0, run.exitCode)
        assertTrue(run.result?.truncated == true)
        assertNull(run.result?.error)
    }

    @Test
    fun `pending run without optional fields decodes`() {
        val json = """
            {
              "id": 8,
              "execution_id": "b0e5c9a0-1e2f-4a3b-9c8d-ffffffffffff",
              "server_id": 1,
              "template_id": "uptime",
              "rendered_command": "uptime",
              "status": "pending_approval",
              "source": "manual"
            }
        """.trimIndent()

        val run: CommandRun = AppJson.decodeFromString(json)
        assertEquals("pending_approval", run.status)
        assertTrue(run.params.isEmpty())
        assertNull(run.proposal)
        assertNull(run.result)
        assertNull(run.exitCode)
        assertNull(run.completedAt)
    }

    @Test
    fun `template decodes with argv and param patterns`() {
        val json = """
            {
              "id": "disk_top_usage",
              "argv": ["du", "-h", "--max-depth=1", "{path}", "|", "sort", "-rh", "|", "head", "-20"],
              "params": [{"name": "path", "pattern": "^[A-Za-z0-9/_.-]{1,128}$"}]
            }
        """.trimIndent()

        val template: CommandTemplate = AppJson.decodeFromString(json)
        assertEquals("disk_top_usage", template.id)
        assertEquals(10, template.argv.size)
        assertEquals("path", template.params[0].name)
    }

    @Test
    fun `status final state helper matches backend lifecycle`() {
        assertTrue(CommandStatusValues.isFinal(CommandStatusValues.SUCCEEDED))
        assertTrue(CommandStatusValues.isFinal(CommandStatusValues.FAILED))
        assertTrue(CommandStatusValues.isFinal(CommandStatusValues.REJECTED))
        assertTrue(CommandStatusValues.isFinal(CommandStatusValues.EXPIRED))
        assertTrue(CommandStatusValues.isFinal(CommandStatusValues.TIMEOUT))
        assertFalse(CommandStatusValues.isFinal(CommandStatusValues.PENDING_APPROVAL))
        assertFalse(CommandStatusValues.isFinal(CommandStatusValues.APPROVED))
        assertFalse(CommandStatusValues.isFinal(CommandStatusValues.EXECUTING))
    }

    @Test
    fun `page result of command runs decodes`() {
        val json = """
            {
              "code": 0, "message": "success",
              "data": {
                "items": [
                  {"id": 1, "execution_id": "e", "server_id": 1, "template_id": "uptime",
                   "rendered_command": "uptime", "status": "rejected", "source": "ai"}
                ],
                "total": 1, "page": 1, "page_size": 20
              }
            }
        """.trimIndent()

        val response: ApiResponse<PageResult<CommandRun>> = AppJson.decodeFromString(json)
        val page = response.data
        assertNotNull(page)
        assertEquals(1L, page!!.total)
        assertEquals("rejected", page.items[0].status)
    }

    @Test
    fun `command run decodes risk level and approval mode`() {
        val json = """
            {"id": 7, "execution_id": "e7", "server_id": 8, "template_id": "uptime",
             "rendered_command": "uptime", "status": "executing", "source": "ai",
             "risk_level": "low", "approval_mode": "auto"}
        """.trimIndent()

        val run: CommandRun = AppJson.decodeFromString(json)
        assertEquals("low", run.riskLevel)
        assertEquals("auto", run.approvalMode)
    }

    @Test
    fun `legacy run without risk fields decodes with defaults`() {
        val json = """
            {"id": 8, "execution_id": "e8", "server_id": 8, "template_id": "uptime",
             "rendered_command": "uptime", "status": "succeeded", "source": "manual"}
        """.trimIndent()

        val run: CommandRun = AppJson.decodeFromString(json)
        assertNull(run.riskLevel)
        assertNull(run.approvalMode)
    }

    @Test
    fun `auto approval policy decodes and encodes request`() {
        val snapshot = """
            {"enabled": true, "max_risk_level": "medium", "updated_at": "2026-09-10T00:00:00Z", "updated_by": 2}
        """.trimIndent()

        val policy: AutoApprovalPolicy = AppJson.decodeFromString(snapshot)
        assertTrue(policy.enabled)
        assertEquals("medium", policy.maxRiskLevel)
        assertEquals(2L, policy.updatedBy)

        val encoded = AppJson.encodeToString(AutoApprovalPolicyRequest.serializer(), AutoApprovalPolicyRequest(true, "low"))
        assertTrue(encoded.contains("\"max_risk_level\":\"low\""))
        assertTrue(encoded.contains("\"enabled\":true"))
    }
}

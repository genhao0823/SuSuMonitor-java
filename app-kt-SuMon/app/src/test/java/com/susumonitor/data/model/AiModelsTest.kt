package com.susumonitor.data.model

import com.susumonitor.data.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI DTO 反序列化测试：JSON 样本取自 OpenAPI `openapi-ai.json` 契约示例。
 */
class AiModelsTest {

    @Test
    fun `diagnosis response decodes from openapi example`() {
        val json = """
            {
              "code": 0,
              "message": "success",
              "data": {
                "summary": "CPU saturation coincided with elevated load.",
                "severity": "warning",
                "findings": [
                  {"title": "Sustained CPU", "description": "CPU above range.", "confidence": "medium"}
                ],
                "evidence": [
                  {"metric": "cpu_percent", "value": 92.5, "observed_at": "2026-08-30T10:00:00Z", "source": "monitoring_summary"}
                ],
                "recommendations": ["Review the workload."],
                "limitations": ["Read-only analysis."],
                "model_used": true,
                "provider": "approved-provider",
                "model": "approved-model",
                "prompt_version": "ai-diagnosis-v1",
                "usage": {
                  "input_tokens": 850, "output_tokens": 220, "total_tokens": 1070,
                  "estimated_cost": 0.0021, "currency": "USD"
                }
              }
            }
        """.trimIndent()

        val response: ApiResponse<AiDiagnosis> = AppJson.decodeFromString(json)
        val diagnosis = response.data
        assertNotNull(diagnosis)
        assertEquals("warning", diagnosis!!.severity)
        assertTrue(diagnosis.modelUsed)
        assertEquals(1, diagnosis.findings.size)
        assertEquals("medium", diagnosis.findings[0].confidence)
        assertEquals(1070, diagnosis.usage?.totalTokens)
        assertEquals("USD", diagnosis.usage?.currency)
    }

    @Test
    fun `evidence value accepts number string and null`() {
        val number: AiEvidence = AppJson.decodeFromString(
            """{"metric":"cpu_percent","value":92.5,"observed_at":"2026-08-30T10:00:00Z","source":"monitoring_summary"}""",
        )
        assertEquals("92.5", number.value?.jsonPrimitive?.content)

        val text: AiEvidence = AppJson.decodeFromString(
            """{"metric":"status","value":"degraded","observed_at":"2026-08-30T10:00:00Z","source":"alert_summary"}""",
        )
        assertEquals("degraded", text.value?.jsonPrimitive?.content)

        val absent: AiEvidence = AppJson.decodeFromString(
            """{"metric":"load","value":null,"observed_at":"2026-08-30T10:00:00Z","source":"monitoring_summary"}""",
        )
        assertNull(absent.value)
    }

    @Test
    fun `qa response decodes with tool calls and degraded flags`() {
        val json = """
            {
              "code": 0, "message": "success",
              "data": {
                "answer": "CPU is at 23%.",
                "tool_calls": [
                  {"tool": "get_latest_metrics", "args": "{\"server_id\":123}"},
                  {"tool": "list_alert_records", "args": "{\"status\":\"unread\"}"}
                ],
                "model_used": false,
                "degraded": true,
                "provider": "openai-compatible",
                "model": "approved-model",
                "prompt_version": "ai-qa-v1",
                "usage": {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0, "estimated_cost": 0, "currency": "USD"}
              }
            }
        """.trimIndent()

        val response: ApiResponse<AiQa> = AppJson.decodeFromString(json)
        val qa = response.data
        assertNotNull(qa)
        assertFalse(qa!!.modelUsed)
        assertTrue(qa.degraded)
        assertEquals(2, qa.toolCalls.size)
        assertEquals("get_latest_metrics", qa.toolCalls[0].tool)
    }

    @Test
    fun `qa request omits null server_id for global questions`() {
        val encoded = AppJson.encodeToString(AiQaRequest(serverId = null, question = "集群状态如何？"))
        assertFalse(encoded.contains("server_id"))

        val targeted = AppJson.encodeToString(AiQaRequest(serverId = 3, question = "CPU 情况？"))
        assertTrue(targeted.contains(""""server_id":3"""))
    }

    @Test
    fun `alert explanation decodes from contract fields`() {
        val json = """
            {
              "record_id": 102,
              "summary": "磁盘写入突增导致 IO 等待。",
              "possible_causes": ["备份任务", "日志暴涨"],
              "impact": ["写入延迟上升"],
              "suggestions": ["检查 crontab"],
              "limitations": ["只读分析，不含可执行指令"],
              "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3, "estimated_cost": 0.0, "currency": "USD"},
              "provider": "p", "model": "m", "prompt_version": "v1",
              "created_at": "2026-09-05T08:00:00Z"
            }
        """.trimIndent()

        val explanation: AiAlertExplanation = AppJson.decodeFromString(json)
        assertEquals(102L, explanation.recordId)
        assertEquals(2, explanation.possibleCauses.size)
        assertEquals("检查 crontab", explanation.suggestions[0])
    }
}

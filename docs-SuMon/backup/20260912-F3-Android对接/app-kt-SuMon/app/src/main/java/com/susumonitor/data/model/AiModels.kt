package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** AI 诊断严重级别（与 OpenAPI `AiDiagnosis.severity` 对齐）。 */
object AiSeverityValues {
    const val INFO = "info"
    const val WARNING = "warning"
    const val CRITICAL = "critical"
    const val UNKNOWN = "unknown"
}

/** AI 结论置信度（与 OpenAPI `AiFinding.confidence` 对齐）。 */
object AiConfidenceValues {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val UNKNOWN = "unknown"
}

/** AI 诊断请求，与 OpenAPI `AiDiagnosisRequest` 对齐（admin 专用）。 */
@Serializable
data class AiDiagnosisRequest(
    @SerialName("server_id") val serverId: Long,
    val question: String,
    /** 历史时间窗（分钟），后端允许 5~1440。 */
    @SerialName("history_minutes") val historyMinutes: Int,
)

/** AI Token 消耗统计，与 OpenAPI `AiUsage` 对齐。 */
@Serializable
data class AiUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    val currency: String = "",
)

/** 诊断发现项，与 OpenAPI `AiFinding` 对齐。 */
@Serializable
data class AiFinding(
    val title: String,
    val description: String,
    val confidence: String,
)

/** 指标证据项，与 OpenAPI `AiEvidence` 对齐。value 为数字或字符串，统一按 JsonElement 承载。 */
@Serializable
data class AiEvidence(
    val metric: String,
    val value: JsonElement? = null,
    @SerialName("observed_at") val observedAt: String,
    val source: String,
)

/** AI 只读诊断结果，与 OpenAPI `AiDiagnosis` 对齐。model_used=false 表示确定性兜底摘要。 */
@Serializable
data class AiDiagnosis(
    val summary: String,
    val severity: String,
    val findings: List<AiFinding> = emptyList(),
    val evidence: List<AiEvidence> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    @SerialName("model_used") val modelUsed: Boolean,
    val provider: String = "",
    val model: String = "",
    @SerialName("prompt_version") val promptVersion: String = "",
    val usage: AiUsage? = null,
)

/** AI 问答请求，与 OpenAPI `AiQaRequest` 对齐；server_id 为 null 表示全局提问（序列化时省略）。 */
@Serializable
data class AiQaRequest(
    @SerialName("server_id") val serverId: Long? = null,
    val question: String,
)

/** 问答只读工具调用审计项，与 OpenAPI `AiToolCall` 对齐。 */
@Serializable
data class AiToolCall(
    val tool: String,
    val args: String,
)

/** AI 问答结果，与 OpenAPI `AiQa` 对齐。 */
@Serializable
data class AiQa(
    val answer: String,
    @SerialName("tool_calls") val toolCalls: List<AiToolCall> = emptyList(),
    @SerialName("model_used") val modelUsed: Boolean,
    val degraded: Boolean,
    val provider: String = "",
    val model: String = "",
    @SerialName("prompt_version") val promptVersion: String = "",
    val usage: AiUsage? = null,
)

/** 告警智能解释，与后端 `AiAlertExplanationVo` 对齐（异步生成后回看）。 */
@Serializable
data class AiAlertExplanation(
    @SerialName("record_id") val recordId: Long,
    val summary: String,
    @SerialName("possible_causes") val possibleCauses: List<String> = emptyList(),
    val impact: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val usage: AiUsage? = null,
    val provider: String = "",
    val model: String = "",
    @SerialName("prompt_version") val promptVersion: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

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

/** 定时健康报告生成状态（与 OpenAPI `AiHealthReport.status` 枚举对齐）。 */
object AiHealthReportStatusValues {
    const val SUCCEEDED = "succeeded"
    const val DEGRADED = "degraded"
}

/** 报告覆盖窗口内的服务器清单快照（与 OpenAPI `AiHealthReportFacts.server_inventory` 对齐）。 */
@Serializable
data class AiHealthReportServerInventory(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("online_count") val onlineCount: Int,
    @SerialName("offline_count") val offlineCount: Int,
    @SerialName("online_rate") val onlineRate: Double,
)

/** 报告窗口内指标均值与峰值（与 OpenAPI `AiHealthReportFacts.metric_peaks` 对齐；窗口无采样时为 null）。 */
@Serializable
data class AiHealthReportMetricPeaks(
    @SerialName("avg_cpu_percent") val avgCpuPercent: Double? = null,
    @SerialName("max_cpu_percent") val maxCpuPercent: Double? = null,
    @SerialName("max_cpu_server_id") val maxCpuServerId: Long? = null,
    @SerialName("avg_memory_percent") val avgMemoryPercent: Double? = null,
    @SerialName("max_memory_percent") val maxMemoryPercent: Double? = null,
    @SerialName("max_memory_server_id") val maxMemoryServerId: Long? = null,
    @SerialName("avg_disk_percent") val avgDiskPercent: Double? = null,
    @SerialName("max_disk_percent") val maxDiskPercent: Double? = null,
    @SerialName("max_disk_server_id") val maxDiskServerId: Long? = null,
)

/** 报告窗口内告警最多的服务器（与 OpenAPI `AiHealthReportFacts.top_servers` items 对齐）。 */
@Serializable
data class AiHealthReportTopAlertServer(
    @SerialName("server_id") val serverId: Long,
    @SerialName("server_name") val serverName: String,
    @SerialName("alert_count") val alertCount: Int,
    @SerialName("critical_count") val criticalCount: Int,
)

/** 报告窗口内的告警统计（与 OpenAPI `AiHealthReportFacts.alert_statistics` 对齐）。 */
@Serializable
data class AiHealthReportAlertStatistics(
    @SerialName("total_triggered") val totalTriggered: Int,
    @SerialName("critical_count") val criticalCount: Int,
    @SerialName("warning_count") val warningCount: Int,
    @SerialName("resolved_count") val resolvedCount: Int,
    @SerialName("unresolved_count") val unresolvedCount: Int,
    @SerialName("top_servers") val topServers: List<AiHealthReportTopAlertServer> = emptyList(),
)

/** 当前离线服务器快照行（与 OpenAPI `AiHealthReportFacts.offline_servers` items 对齐）。 */
@Serializable
data class AiHealthReportOfflineServer(
    @SerialName("server_id") val serverId: Long,
    @SerialName("server_name") val serverName: String,
    @SerialName("agent_status") val agentStatus: String,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: String? = null,
)

/** 服务端聚合的白名单事实快照（与 OpenAPI `AiHealthReportFacts` 对齐，事实以此为准）。 */
@Serializable
data class AiHealthReportFacts(
    @SerialName("report_date") val reportDate: String,
    @SerialName("server_inventory") val serverInventory: AiHealthReportServerInventory,
    @SerialName("metric_peaks") val metricPeaks: AiHealthReportMetricPeaks,
    @SerialName("alert_statistics") val alertStatistics: AiHealthReportAlertStatistics,
    @SerialName("offline_servers") val offlineServers: List<AiHealthReportOfflineServer> = emptyList(),
)

/**
 * 定时健康报告，与 OpenAPI `AiHealthReport` 对齐（F3/V34，admin 专用）。
 * status=degraded 时 summary 为 null 且 errorCode 记录降级原因（如 42906）。
 */
@Serializable
data class AiHealthReport(
    val id: Long,
    @SerialName("report_date") val reportDate: String,
    val status: String,
    val provider: String = "",
    val model: String = "",
    @SerialName("prompt_version") val promptVersion: String = "",
    val summary: String? = null,
    @SerialName("top_concerns") val topConcerns: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val facts: AiHealthReportFacts? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val usage: AiUsage? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

/** 手动触发健康报告生成请求（与 OpenAPI `GenerateHealthReportRequest` 对齐）；日期缺省由后端取昨日。 */
@Serializable
data class GenerateHealthReportRequest(
    @SerialName("report_date") val reportDate: String? = null,
)

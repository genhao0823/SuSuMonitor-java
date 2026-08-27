package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 固定宽表指标（最新值/历史行共用），与 OpenAPI `MetricsLatest`/`MetricsHistory` 对齐。
 * 数值字段在后端缺省时为 null。
 */
@Serializable
data class Metrics(
    @SerialName("server_id") val serverId: Long = 0,
    @SerialName("cpu_percent") val cpuPercent: Double? = null,
    @SerialName("memory_percent") val memoryPercent: Double? = null,
    @SerialName("memory_used") val memoryUsed: Double? = null,
    @SerialName("memory_total") val memoryTotal: Double? = null,
    @SerialName("disk_percent") val diskPercent: Double? = null,
    @SerialName("disk_used") val diskUsed: Double? = null,
    @SerialName("disk_total") val diskTotal: Double? = null,
    @SerialName("net_rx") val netRx: Double? = null,
    @SerialName("net_tx") val netTx: Double? = null,
    val temperature: Double? = null,
    @SerialName("load_avg") val loadAvg: Double? = null,
    @SerialName("collected_at") val collectedAt: String,
)

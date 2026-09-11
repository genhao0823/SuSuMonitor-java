package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/api/health` 响应数据。 */
@Serializable
data class HealthStatus(
    val status: String,
    val application: String,
    val timestamp: String,
)

/** `/api/ready` 响应数据。 */
@Serializable
data class ReadyStatus(
    val status: String,
    val database: String,
    val timestamp: String,
)

/** `/api/ws/monitor-ticket` 响应数据。 */
@Serializable
data class MonitorTicket(
    val ticket: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** RabbitMQ 队列积压快照，与后端 `QueueBacklogVo` 对齐。 */
@Serializable
data class QueueBacklog(
    val queue: String,
    val type: String,
    val messages: Long,
    @SerialName("warn_threshold") val warnThreshold: Int,
    @SerialName("checked_at") val checkedAt: String,
    val error: Boolean = false,
)

/** RabbitMQ 消费者吞吐统计，与后端 `ConsumeStatsVo` 对齐。 */
@Serializable
data class ConsumeStats(
    val consumer: String,
    @SerialName("total_count") val totalCount: Long,
    @SerialName("avg_ms") val avgMs: Long,
    @SerialName("max_ms") val maxMs: Long,
    @SerialName("last_sample_at") val lastSampleAt: String? = null,
    @SerialName("failed_window") val failedWindow: Long,
    @SerialName("consumed_window") val consumedWindow: Long,
    @SerialName("failure_rate") val failureRate: Double,
)

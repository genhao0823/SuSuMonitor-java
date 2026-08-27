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

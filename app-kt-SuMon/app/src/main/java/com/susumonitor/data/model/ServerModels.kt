package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 服务器公开 VO，与 OpenAPI `Server` 对齐（不含凭据明文或密文）。 */
@Serializable
data class Server(
    val id: Long,
    val name: String,
    val host: String,
    val description: String? = null,
    val status: String,
    @SerialName("ssh_host") val sshHost: String,
    @SerialName("ssh_port") val sshPort: Int,
    @SerialName("ssh_user") val sshUser: String,
    @SerialName("ssh_auth_type") val sshAuthType: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("agent_status") val agentStatus: String,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** 服务器状态快照，与 OpenAPI `ServerStatus` 对齐。 */
@Serializable
data class ServerStatus(
    @SerialName("server_id") val serverId: Long,
    val status: String,
    @SerialName("agent_status") val agentStatus: String,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: String? = null,
    @SerialName("delivery_pending_count") val deliveryPendingCount: Long? = null,
    @SerialName("delivery_pending_bytes") val deliveryPendingBytes: Long? = null,
    @SerialName("delivery_oldest_collected_at") val deliveryOldestCollectedAt: String? = null,
    @SerialName("delivery_drop_count") val deliveryDropCount: Long? = null,
    @SerialName("delivery_dead_letter_count") val deliveryDeadLetterCount: Long? = null,
    @SerialName("delivery_dead_letter_bytes") val deliveryDeadLetterBytes: Long? = null,
    @SerialName("checked_at") val checkedAt: String,
)

/** 服务器列表查询参数，与 OpenAPI `listServers` parameters 对齐。 */
data class ServerQuery(
    val page: Int = 1,
    val pageSize: Int = 20,
    val keyword: String? = null,
    val sortBy: String? = null,
    val sortOrder: String? = null,
)

/** Monitor WebSocket 推送的服务器状态转换快照（server.status.update payload）。 */
@Serializable
data class ServerStatusPushPayload(
    @SerialName("server_id") val serverId: Long,
    val status: String,
    @SerialName("agent_status") val agentStatus: String,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: String? = null,
)

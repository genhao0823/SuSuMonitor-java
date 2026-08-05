package com.susumonitor.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Monitor WebSocket 帧外层结构，对齐 `websocket-protocol.md` v1.3 Common Message。
 * `payload` 为任意 JSON 值（对象/数组/字符串），`message_id` 在后端无法关联客户端帧时可为 null。
 */
@Serializable
data class WsFrame(
    val type: String,
    @SerialName("message_id") val messageId: String? = null,
    val timestamp: String? = null,
    val payload: JsonElement? = null,
)

/** metrics.update 帧 payload：订阅的服务器新到指标。 */
@Serializable
data class MetricsUpdatePayload(
    @SerialName("server_id") val serverId: Long,
    val metrics: Metrics,
)

/** error 帧 payload：与 REST 相同数字业务错误码。 */
@Serializable
data class WsErrorPayload(
    val code: Int,
    val message: String,
)

/** Monitor 通道可接收的帧类型常量。 */
object WsFrameType {
    const val METRICS_SUBSCRIBE = "metrics.subscribe"
    const val METRICS_UNSUBSCRIBE = "metrics.unsubscribe"
    const val METRICS_UPDATE = "metrics.update"
    const val ALERT_PUSH = "alert.push"
    const val SERVER_STATUS_UPDATE = "server.status.update"
    const val ERROR = "error"
}

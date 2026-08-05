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

    // 终端帧（浏览器 → /ws/monitor，Agent → /ws/agent 响应）
    const val TERMINAL_OPEN = "terminal.open"
    const val TERMINAL_OPENED = "terminal.opened"
    const val TERMINAL_INPUT = "terminal.input"
    const val TERMINAL_OUTPUT = "terminal.output"
    const val TERMINAL_RESIZE = "terminal.resize"
    const val TERMINAL_CLOSE = "terminal.close"
    const val TERMINAL_CLOSED = "terminal.closed"
    const val TERMINAL_ERROR = "terminal.error"
}

/**
 * terminal.open 帧 payload：浏览器 → /ws/monitor。
 * cols 1-300，rows 1-100。
 */
@Serializable
data class TerminalOpenPayload(
    @SerialName("server_id") val serverId: Long,
    val cols: Int,
    val rows: Int,
)

/**
 * terminal.opened 帧 payload：Agent → /ws/agent → 浏览器。
 * session_id 由 Java 生成（UUID），浏览器不可选择。
 */
@Serializable
data class TerminalOpenedPayload(
    @SerialName("server_id") val serverId: Long,
    @SerialName("session_id") val sessionId: String,
    val shell: String,
)

/**
 * terminal.input / terminal.output 帧 payload。
 * data 为 Base64 编码（解码后 1-16 KiB）。
 */
@Serializable
data class TerminalDataPayload(
    @SerialName("server_id") val serverId: Long? = null,
    @SerialName("session_id") val sessionId: String,
    val data: String,
)

/**
 * terminal.resize 帧 payload：浏览器 → /ws/monitor。
 */
@Serializable
data class TerminalResizePayload(
    @SerialName("server_id") val serverId: Long? = null,
    @SerialName("session_id") val sessionId: String,
    val cols: Int,
    val rows: Int,
)

/** terminal.closed 帧 payload：Agent → 浏览器。 */
@Serializable
data class TerminalClosedPayload(
    @SerialName("server_id") val serverId: Long,
    @SerialName("session_id") val sessionId: String,
    val reason: String,
    @SerialName("exit_code") val exitCode: Int? = null,
)

/** terminal.error 帧 payload。 */
@Serializable
data class TerminalErrorPayload(
    val code: Int,
    val message: String,
)

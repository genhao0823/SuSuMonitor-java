package com.susumonitor.data

import com.susumonitor.data.model.AlertPushPayload
import com.susumonitor.data.model.MetricsUpdatePayload
import com.susumonitor.data.model.ServerStatusPushPayload
import com.susumonitor.data.model.WsErrorPayload
import com.susumonitor.data.model.WsFrame
import com.susumonitor.data.model.WsFrameType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitor WebSocket 帧解析器：外层 WsFrame 的 payload 是任意 JSON 值，
 * 按 type 二次解析为对应业务对象。对齐 websocket-protocol.md v1.3。
 */
@Singleton
class WsMessageParser @Inject constructor(
    private val json: Json,
) {

    /** 解析外层帧。 */
    fun parseFrame(raw: String): WsFrame = json.decodeFromString(WsFrame.serializer(), raw)

    /** 解析 metrics.update 帧 payload。 */
    fun parseMetricsUpdate(payload: JsonElement): MetricsUpdatePayload =
        json.decodeFromJsonElement(MetricsUpdatePayload.serializer(), payload)

    /** 解析 alert.push 帧 payload。 */
    fun parseAlertPush(payload: JsonElement): AlertPushPayload =
        json.decodeFromJsonElement(AlertPushPayload.serializer(), payload)

    /** 解析 server.status.update 帧 payload。 */
    fun parseServerStatusUpdate(payload: JsonElement): ServerStatusPushPayload =
        json.decodeFromJsonElement(ServerStatusPushPayload.serializer(), payload)

    /** 解析 error 帧 payload。 */
    fun parseError(frame: WsFrame): WsErrorPayload {
        val payload = frame.payload ?: return WsErrorPayload(0, "empty error payload")
        return json.decodeFromJsonElement(WsErrorPayload.serializer(), payload)
    }

    /**
     * 按帧类型分发为强类型结果。
     * 未知类型返回 null（忽略即可，契约演进容忍）。
     */
    fun parseByType(frame: WsFrame): WsMessage? = when (frame.type) {
        WsFrameType.METRICS_UPDATE -> frame.payload?.let { WsMessage.MetricsUpdate(parseMetricsUpdate(it)) }
        WsFrameType.ALERT_PUSH -> frame.payload?.let { WsMessage.AlertPush(parseAlertPush(it)) }
        WsFrameType.SERVER_STATUS_UPDATE -> frame.payload?.let { WsMessage.ServerStatusUpdate(parseServerStatusUpdate(it)) }
        WsFrameType.ERROR -> WsMessage.Error(parseError(frame))
        else -> null
    }
}

/** 解析后的 WS 业务消息（sealed 分发结果）。 */
sealed class WsMessage {
    data class MetricsUpdate(val payload: com.susumonitor.data.model.MetricsUpdatePayload) : WsMessage()
    data class AlertPush(val payload: AlertPushPayload) : WsMessage()
    data class ServerStatusUpdate(val payload: ServerStatusPushPayload) : WsMessage()
    data class Error(val payload: WsErrorPayload) : WsMessage()
}

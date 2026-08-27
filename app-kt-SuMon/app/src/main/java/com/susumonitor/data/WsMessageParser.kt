package com.susumonitor.data

import com.susumonitor.data.model.AlertPushPayload
import com.susumonitor.data.model.MetricsUpdatePayload
import com.susumonitor.data.model.ServerStatusPushPayload
import com.susumonitor.data.model.TerminalClosedPayload
import com.susumonitor.data.model.TerminalDataPayload
import com.susumonitor.data.model.TerminalErrorPayload
import com.susumonitor.data.model.TerminalOpenedPayload
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

    /** 解析 terminal.opened 帧 payload。 */
    fun parseTerminalOpened(payload: JsonElement): TerminalOpenedPayload =
        json.decodeFromJsonElement(TerminalOpenedPayload.serializer(), payload)

    /** 解析 terminal.output 帧 payload。 */
    fun parseTerminalOutput(payload: JsonElement): TerminalDataPayload =
        json.decodeFromJsonElement(TerminalDataPayload.serializer(), payload)

    /** 解析 terminal.closed 帧 payload。 */
    fun parseTerminalClosed(payload: JsonElement): TerminalClosedPayload =
        json.decodeFromJsonElement(TerminalClosedPayload.serializer(), payload)

    /** 解析 terminal.error 帧 payload。 */
    fun parseTerminalError(payload: JsonElement): TerminalErrorPayload =
        json.decodeFromJsonElement(TerminalErrorPayload.serializer(), payload)

    /**
     * 按帧类型分发为强类型结果。
     * 未知类型返回 null（忽略即可，契约演进容忍）。
     */
    fun parseByType(frame: WsFrame): WsMessage? = when (frame.type) {
        WsFrameType.METRICS_UPDATE -> frame.payload?.let { WsMessage.MetricsUpdate(parseMetricsUpdate(it)) }
        WsFrameType.ALERT_PUSH -> frame.payload?.let { WsMessage.AlertPush(parseAlertPush(it)) }
        WsFrameType.SERVER_STATUS_UPDATE -> frame.payload?.let { WsMessage.ServerStatusUpdate(parseServerStatusUpdate(it)) }
        WsFrameType.ERROR -> WsMessage.Error(parseError(frame))
        WsFrameType.TERMINAL_OPENED -> frame.payload?.let { WsMessage.TerminalOpened(parseTerminalOpened(it)) }
        WsFrameType.TERMINAL_OUTPUT -> frame.payload?.let { WsMessage.TerminalOutput(parseTerminalOutput(it)) }
        WsFrameType.TERMINAL_CLOSED -> frame.payload?.let { WsMessage.TerminalClosed(parseTerminalClosed(it)) }
        WsFrameType.TERMINAL_ERROR -> frame.payload?.let { WsMessage.TerminalError(parseTerminalError(it)) }
        else -> null
    }
}

/** 解析后的 WS 业务消息（sealed 分发结果）。 */
sealed class WsMessage {
    data class MetricsUpdate(val payload: com.susumonitor.data.model.MetricsUpdatePayload) : WsMessage()
    data class AlertPush(val payload: AlertPushPayload) : WsMessage()
    data class ServerStatusUpdate(val payload: ServerStatusPushPayload) : WsMessage()
    data class Error(val payload: WsErrorPayload) : WsMessage()
    data class TerminalOpened(val payload: TerminalOpenedPayload) : WsMessage()
    data class TerminalOutput(val payload: TerminalDataPayload) : WsMessage()
    data class TerminalClosed(val payload: TerminalClosedPayload) : WsMessage()
    data class TerminalError(val payload: TerminalErrorPayload) : WsMessage()
}

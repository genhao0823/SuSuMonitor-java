package com.susumonitor.api

import android.util.Base64
import com.susumonitor.data.WsMessage
import com.susumonitor.data.model.TerminalClosedPayload
import com.susumonitor.data.model.TerminalDataPayload
import com.susumonitor.data.model.TerminalErrorPayload
import com.susumonitor.data.model.TerminalOpenedPayload
import com.susumonitor.data.model.WsFrameType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/** 终端会话阶段（对齐前端 TerminalPhase）。 */
enum class TerminalPhase {
    IDLE,        // 未开始
    AWAITING_OPEN, // 已发 open，等待 opened
    OPEN,        // 会话已建立
    CLOSING,     // 已发 close
    CLOSED,      // 已关闭
}

/** 终端会话状态（回调给 UI）。 */
data class TerminalSessionState(
    val phase: TerminalPhase = TerminalPhase.IDLE,
    val sessionId: String? = null,
    val shell: String? = null,
    val errorMessage: String? = null,
)

/**
 * 终端会话客户端：复用 [WsClient] 已建立的 /ws/monitor 连接，
 * 按 websocket-protocol.md v1.3 Terminal Messages 收发帧。
 *
 * 状态机：IDLE → AWAITING_OPEN → OPEN → CLOSING → CLOSED。
 * 不自动重连（断开会话丢失，由 UI 重建）。
 */
@Singleton
class TerminalClient @Inject constructor(
    private val wsClient: WsClient,
) {

    @Volatile
    var state: TerminalSessionState = TerminalSessionState()
        private set

    /** 状态变更回调（UI 层订阅）。 */
    @Volatile
    var onStateChanged: ((TerminalSessionState) -> Unit)? = null

    /** 收到 PTY 输出（Base64 解码后的原始字节）。 */
    @Volatile
    var onOutput: ((ByteArray) -> Unit)? = null

    private val parser = com.susumonitor.data.WsMessageParser(com.susumonitor.data.AppJson)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var collectJob: Job? = null

    /** 开始收集 WsClient 的 terminal 消息流（幂等）。 */
    fun startCollecting() {
        if (collectJob != null) return
        collectJob = scope.launch {
            wsClient.messages.collect { message ->
                handleIncoming(message)
            }
        }
    }

    /**
     * 打开终端会话。
     * @param serverId 目标服务器
     * @param cols 初始列数（1-300）
     * @param rows 初始行数（1-100）
     */
    fun open(serverId: Long, cols: Int, rows: Int): Boolean {
        if (state.phase == TerminalPhase.AWAITING_OPEN || state.phase == TerminalPhase.OPEN) return false
        val payload = buildJsonObject {
            put("server_id", serverId)
            put("cols", cols.coerceIn(1, 300))
            put("rows", rows.coerceIn(1, 100))
        }
        val sent = wsClient.sendTerminalFrame(WsFrameType.TERMINAL_OPEN, payload)
        if (sent) {
            updateState(TerminalSessionState(phase = TerminalPhase.AWAITING_OPEN))
        }
        return sent
    }

    /** 发送终端输入（open 状态）。 */
    fun sendInput(data: ByteArray) {
        val sessionId = state.sessionId ?: return
        if (state.phase != TerminalPhase.OPEN) return
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("data", encoded)
        }
        wsClient.sendTerminalFrame(WsFrameType.TERMINAL_INPUT, payload)
    }

    /** 发送文本输入（便捷方法）。 */
    fun sendInput(text: String) {
        sendInput(text.toByteArray(StandardCharsets.UTF_8))
    }

    /** 调整终端尺寸（open 状态）。 */
    fun resize(cols: Int, rows: Int) {
        val sessionId = state.sessionId ?: return
        if (state.phase != TerminalPhase.OPEN) return
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("cols", cols.coerceIn(1, 300))
            put("rows", rows.coerceIn(1, 100))
        }
        wsClient.sendTerminalFrame(WsFrameType.TERMINAL_RESIZE, payload)
    }

    /** 关闭终端会话。 */
    fun close() {
        val sessionId = state.sessionId
        val payload = buildJsonObject {
            if (sessionId != null) put("session_id", sessionId)
        }
        wsClient.sendTerminalFrame(WsFrameType.TERMINAL_CLOSE, payload)
        if (sessionId != null) {
            updateState(TerminalSessionState(phase = TerminalPhase.CLOSING, sessionId = sessionId))
        }
    }

    /** 强制关闭（socket 断开兜底）。 */
    fun forceClosed() {
        updateState(TerminalSessionState(phase = TerminalPhase.CLOSED))
    }

    /** 重置到初始状态（重试用）。 */
    fun reset() {
        updateState(TerminalSessionState(phase = TerminalPhase.IDLE))
    }

    /** 处理收到的 terminal 消息。 */
    private fun handleIncoming(message: WsMessage) {
        when (message) {
            is WsMessage.TerminalOpened -> handleOpened(message.payload)
            is WsMessage.TerminalOutput -> handleOutput(message.payload)
            is WsMessage.TerminalClosed -> handleClosed(message.payload)
            is WsMessage.TerminalError -> handleError(message.payload)
            else -> Unit // 其他消息忽略
        }
    }

    private fun handleOpened(payload: TerminalOpenedPayload) {
        updateState(
            TerminalSessionState(
                phase = TerminalPhase.OPEN,
                sessionId = payload.sessionId,
                shell = payload.shell,
            ),
        )
    }

    private fun handleOutput(payload: TerminalDataPayload) {
        if (state.phase != TerminalPhase.OPEN) return
        val bytes = runCatching {
            Base64.decode(payload.data, Base64.NO_WRAP)
        }.getOrNull() ?: return
        onOutput?.invoke(bytes)
    }

    private fun handleClosed(payload: TerminalClosedPayload) {
        updateState(
            TerminalSessionState(
                phase = TerminalPhase.CLOSED,
                sessionId = payload.sessionId,
                errorMessage = payload.reason,
            ),
        )
    }

    private fun handleError(payload: TerminalErrorPayload) {
        updateState(
            state.copy(
                errorMessage = "终端错误 (${payload.code}): ${payload.message}",
            ),
        )
    }

    private fun updateState(newState: TerminalSessionState) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}

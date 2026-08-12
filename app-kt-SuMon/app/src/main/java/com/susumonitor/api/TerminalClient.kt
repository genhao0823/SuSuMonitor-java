package com.susumonitor.api

import android.util.Log
import com.susumonitor.data.WsMessage
import com.susumonitor.data.model.TerminalClosedPayload
import com.susumonitor.data.model.TerminalDataPayload
import com.susumonitor.data.model.TerminalErrorPayload
import com.susumonitor.data.model.TerminalOpenedPayload
import com.susumonitor.data.model.WsFrameType
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** 终端会话阶段（对齐前端 TerminalPhase + Android 扩展 RECONNECTING）。 */
enum class TerminalPhase {
    IDLE,        // 未开始
    AWAITING_OPEN, // 已发 open，等待 opened
    OPEN,        // 会话已建立
    CLOSING,     // 已发 close
    CLOSED,      // 已关闭
    RECONNECTING, // 断线后等待自动重连
}

/** 终端会话状态（回调给 UI）。 */
data class TerminalSessionState(
    val phase: TerminalPhase = TerminalPhase.IDLE,
    val sessionId: String? = null,
    val shell: String? = null,
    val errorMessage: String? = null,
    val reconnectAttempts: Int = 0,
)

/** Agent 侧正常退出（shell 内执行 exit 等）的关闭原因，不触发自动重连。 */
private const val REASON_PROCESS_EXITED = "process_exited"

/**
 * 终端会话客户端：复用 [WsClient] 已建立的 /ws/monitor 连接，
 * 按 websocket-protocol.md v1.3 Terminal Messages 收发帧。
 *
 * 状态机：IDLE → AWAITING_OPEN → OPEN → CLOSING → CLOSED；
 * 断线自动重连：WS 断开或异常关闭（非用户主动、非 shell 正常退出）时
 * 指数退避（1s→30s + 抖动）重新 open 原尺寸会话，成功建立后尝试计数归零。
 */
@Singleton
class TerminalClient @Inject constructor(
    private val wsClient: WsClient,
) {

    private companion object {
        private const val TAG = "TerminalClient"
    }

    @Volatile
    var state: TerminalSessionState = TerminalSessionState()
        private set

    /** 状态变更回调（UI 层订阅）。 */
    @Volatile
    var onStateChanged: ((TerminalSessionState) -> Unit)? = null

    /** 收到 PTY 输出（Base64 解码后的原始字节）。 */
    @Volatile
    var onOutput: ((ByteArray) -> Unit)? = null

    /** 重连退避初始延迟（测试可调小）。 */
    internal var reconnectInitialDelayMs: Long = 1_000L

    /** 重连退避最大延迟。 */
    internal var reconnectMaxDelayMs: Long = 30_000L

    private val parser = com.susumonitor.data.WsMessageParser(com.susumonitor.data.AppJson)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var collectJob: Job? = null

    @Volatile
    private var connectionJob: Job? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var lastServerId: Long = 0L

    @Volatile
    private var lastCols: Int = 80

    @Volatile
    private var lastRows: Int = 24

    /** 用户主动关闭意图（close()/离开页面置位，阻断自动重连）。 */
    @Volatile
    private var userClosed = false

    /** 当前连续自动重连尝试次数（成功建立会话后归零）。 */
    @Volatile
    private var reconnectAttempts = 0

    /** 开始收集 WsClient 的 terminal 消息流与连接状态（幂等）。 */
    fun startCollecting() {
        if (collectJob != null) return
        collectJob = scope.launch {
            wsClient.messages.collect { message ->
                handleIncoming(message)
            }
        }
        connectionJob = scope.launch {
            wsClient.connectionState.collect { connectionState ->
                handleConnectionState(connectionState)
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
        lastServerId = serverId
        lastCols = cols.coerceIn(1, 300)
        lastRows = rows.coerceIn(1, 100)
        // 用户主动 open（首开/手动重试）视为新会话意图，重置重连状态
        userClosed = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        val payload = buildJsonObject {
            put("server_id", serverId)
            put("cols", cols.coerceIn(1, 300))
            put("rows", rows.coerceIn(1, 100))
        }
        val sent = wsClient.sendTerminalFrame(WsFrameType.TERMINAL_OPEN, payload)
        Log.d(TAG, "open server=$serverId sent=$sent phase=${state.phase}")
        if (sent) {
            updateState(TerminalSessionState(phase = TerminalPhase.AWAITING_OPEN, reconnectAttempts = 0))
        }
        return sent
    }

    /** 发送终端输入（open 状态）。 */
    fun sendInput(data: ByteArray) {
        val sessionId = state.sessionId ?: return
        if (state.phase != TerminalPhase.OPEN) return
        // java.util.Base64：与 Android NO_WRAP 等价（带 padding、无换行），纯 JVM 可单测
        val encoded = Base64.getEncoder().encodeToString(data)
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
        lastCols = cols.coerceIn(1, 300)
        lastRows = rows.coerceIn(1, 100)
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("cols", cols.coerceIn(1, 300))
            put("rows", rows.coerceIn(1, 100))
        }
        wsClient.sendTerminalFrame(WsFrameType.TERMINAL_RESIZE, payload)
    }

    /** 关闭终端会话（用户主动：置位意图标记，不再自动重连）。 */
    fun close() {
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
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
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        updateState(TerminalSessionState(phase = TerminalPhase.CLOSED))
    }

    /** 重置到初始状态（手动重试用，重连意图清空）。 */
    fun reset() {
        userClosed = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
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

    /** 监听监控通道连接状态：断线标记重连，恢复后立即重开会话。 */
    private fun handleConnectionState(connectionState: ConnectionState) {
        when {
            connectionState == ConnectionState.CONNECTED -> {
                // 通道已恢复且存在待恢复会话 → 取消退避定时器立即重开
                if (state.phase == TerminalPhase.RECONNECTING && !userClosed && lastServerId > 0) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reopenSession()
                }
            }
            connectionState != ConnectionState.CONNECTED -> {
                // 通道断开：会话存活且非用户主动关闭 → 进入重连态（退避由调度任务执行）
                if (!userClosed && lastServerId > 0 &&
                    state.phase in setOf(TerminalPhase.AWAITING_OPEN, TerminalPhase.OPEN)
                ) {
                    reconnectAttempts++
                    updateState(
                        state.copy(phase = TerminalPhase.RECONNECTING, errorMessage = null,
                            reconnectAttempts = reconnectAttempts),
                    )
                    scheduleReopen()
                }
            }
        }
    }

    /** 指数退避重开：等待监控通道就绪后重发 open；失败则继续退避。 */
    private fun scheduleReopen() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive && !userClosed && lastServerId > 0) {
                val delayMs = backoffDelayMs(reconnectAttempts)
                updateState(
                    state.copy(phase = TerminalPhase.RECONNECTING, reconnectAttempts = reconnectAttempts),
                )
                delay(delayMs)
                if (userClosed || !isActive) return@launch
                wsClient.ensureConnected(lastServerId)
                val connected = withTimeoutOrNull(10_000) {
                    wsClient.connectionState.first { it == ConnectionState.CONNECTED }
                }
                if (connected == null) {
                    reconnectAttempts++
                    continue
                }
                if (reopenSession()) {
                    return@launch // 已重发 open，等待 terminal.opened
                }
                reconnectAttempts++
            }
        }
    }

    /** 重新发送 open（复用最近一次尺寸）。成功发送返回 true。 */
    private fun reopenSession(): Boolean {
        val sent = open(lastServerId, lastCols, lastRows)
        if (!sent && state.phase != TerminalPhase.AWAITING_OPEN && state.phase != TerminalPhase.OPEN) {
            Log.w(TAG, "reopen send failed, attempts=${reconnectAttempts}")
        }
        return sent
    }

    /** 重连退避：1s/2s/4s…上限 30s，附加 [1/2, 1] 抖动（对齐 WsClient 策略）。 */
    private fun backoffDelayMs(attempt: Int): Long {
        val capped = attempt.coerceIn(1, 6)
        val base = (reconnectInitialDelayMs shl (capped - 1)).coerceAtMost(reconnectMaxDelayMs)
        val jitter = Random.nextLong(base / 2, base + 1)
        return jitter.coerceAtMost(reconnectMaxDelayMs)
    }

    private fun handleOpened(payload: TerminalOpenedPayload) {
        reconnectAttempts = 0
        updateState(
            TerminalSessionState(
                phase = TerminalPhase.OPEN,
                sessionId = payload.sessionId,
                shell = payload.shell,
                reconnectAttempts = 0,
            ),
        )
    }

    private fun handleOutput(payload: TerminalDataPayload) {
        if (state.phase != TerminalPhase.OPEN) return
        val bytes = runCatching {
            Base64.getDecoder().decode(payload.data)
        }.getOrNull() ?: return
        onOutput?.invoke(bytes)
    }

    private fun handleClosed(payload: TerminalClosedPayload) {
        val normalExit = payload.reason == REASON_PROCESS_EXITED
        if (!normalExit && !userClosed && lastServerId > 0) {
            // 异常关闭（agent 断开/超时等）→ 自动重连
            reconnectAttempts++
            scheduleReopen()
        }
        updateState(
            TerminalSessionState(
                phase = TerminalPhase.CLOSED,
                sessionId = payload.sessionId,
                errorMessage = if (normalExit) null else payload.reason,
                reconnectAttempts = reconnectAttempts,
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

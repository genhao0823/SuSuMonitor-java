package com.susumonitor.ui.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.api.TerminalClient
import com.susumonitor.api.TerminalPhase
import com.susumonitor.api.TerminalSessionState
import com.susumonitor.api.WsClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 终端页 UI 状态。 */
data class TerminalUiState(
    val phase: TerminalPhase = TerminalPhase.IDLE,
    val sessionId: String? = null,
    val shell: String? = null,
    val errorMessage: String? = null,
    val ready: Boolean = false,
    val reconnectAttempts: Int = 0,
)

/**
 * 终端页 ViewModel：驱动 TerminalClient 状态机，向 UI 暴露会话状态。
 *
 * 终端缓冲 [buffer] 与版本号随 ViewModel 存活，配置变更（旋转）后内容不丢失；
 * 版本号以 StateFlow 暴露，UI 收集后驱动重组。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val terminalClient: TerminalClient,
    private val wsClient: WsClient,
) : ViewModel() {

    val serverId: Long = savedStateHandle.get<Long>("serverId") ?: 0L

    /** ANSI 终端缓冲（跨配置变更保留，旋转不清屏）。 */
    val buffer = TerminalBuffer()

    private val _bufferVersion = MutableStateFlow(0L)
    val bufferVersion: StateFlow<Long> = _bufferVersion.asStateFlow()

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    init {
        terminalClient.startCollecting()
        terminalClient.onStateChanged = { state ->
            _uiState.value = mapState(state)
        }
        // 终端输出直接喂入本地缓冲；CSI 状态报告应答回传 PTY 输入通道。
        terminalClient.onOutput = { bytes ->
            buffer.feed(bytes)
            _bufferVersion.update { it + 1 }
        }
        buffer.reportOutput = { report -> sendInput(report.toByteArray(Charsets.UTF_8)) }
        // 初始同步当前状态
        _uiState.value = mapState(terminalClient.state)
    }

    /** 打开终端（首次 cols/rows 由 UI 提供）。 */
    fun open(cols: Int, rows: Int) {
        if (serverId <= 0) return
        viewModelScope.launch {
            // 确保 WS 已连接并订阅（终端依赖 /ws/monitor 通道）
            wsClient.ensureConnected(serverId)
            // 等待连接建立（最多 8 秒，每 300ms 重试 open）
            repeat(27) { attempt ->
                if (terminalClient.state.phase == TerminalPhase.AWAITING_OPEN ||
                    terminalClient.state.phase == TerminalPhase.OPEN
                ) {
                    return@launch
                }
                val sent = terminalClient.open(serverId, cols, rows)
                if (sent && terminalClient.state.phase == TerminalPhase.AWAITING_OPEN) {
                    return@launch
                }
                kotlinx.coroutines.delay(300)
                if (attempt == 26) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "无法连接监控通道，请检查网络或稍后重试",
                    )
                }
            }
        }
    }

    /** 发送输入（由 Termux TerminalView 键盘事件触发）。 */
    fun sendInput(data: ByteArray) {
        terminalClient.sendInput(data)
    }

    /** 调整尺寸。 */
    fun resize(cols: Int, rows: Int) {
        terminalClient.resize(cols, rows)
    }

    /** 关闭终端。 */
    fun close() {
        terminalClient.close()
    }

    /** 重新打开（重置后 open）。 */
    fun retry(cols: Int, rows: Int) {
        terminalClient.reset()
        open(cols, rows)
    }

    /** 标记 UI 渲染就绪（TerminalView attach 完成后）。 */
    fun markReady() {
        _uiState.value = _uiState.value.copy(ready = true)
    }

    /** 清空本地缓冲并递增版本（新会话建立时由 UI 调用）。 */
    fun clearBuffer() {
        buffer.clear()
        _bufferVersion.update { it + 1 }
    }

    /** 本地缓冲网格调整并递增版本（驱动 UI 重绘；服务端 resize 走 [resize]）。 */
    fun resizeBuffer(cols: Int, rows: Int) {
        buffer.resize(cols, rows)
        _bufferVersion.update { it + 1 }
    }

    override fun onCleared() {
        terminalClient.onStateChanged = null
        terminalClient.onOutput = null
        terminalClient.close()
        super.onCleared()
    }

    private fun mapState(state: TerminalSessionState): TerminalUiState = TerminalUiState(
        phase = state.phase,
        sessionId = state.sessionId,
        shell = state.shell,
        errorMessage = state.errorMessage,
        ready = _uiState.value.ready,
        reconnectAttempts = state.reconnectAttempts,
    )
}

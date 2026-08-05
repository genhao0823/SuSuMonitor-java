package com.susumonitor.ui.terminal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.api.TerminalClient
import com.susumonitor.api.TerminalPhase
import com.susumonitor.api.TerminalSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 终端页 UI 状态。 */
data class TerminalUiState(
    val phase: TerminalPhase = TerminalPhase.IDLE,
    val sessionId: String? = null,
    val shell: String? = null,
    val errorMessage: String? = null,
    val ready: Boolean = false,
)

/**
 * 终端页 ViewModel：驱动 TerminalClient 状态机，向 UI 暴露会话状态。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val terminalClient: TerminalClient,
) : ViewModel() {

    val serverId: Long = savedStateHandle.get<Long>("serverId") ?: 0L

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    init {
        terminalClient.startCollecting()
        terminalClient.onStateChanged = { state ->
            _uiState.value = mapState(state)
        }
        // 初始同步当前状态
        _uiState.value = mapState(terminalClient.state)
    }

    /** 打开终端（首次 cols/rows 由 UI 提供）。 */
    fun open(cols: Int, rows: Int) {
        if (serverId <= 0) return
        val sent = terminalClient.open(serverId, cols, rows)
        if (!sent && terminalClient.state.phase != TerminalPhase.AWAITING_OPEN) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "WebSocket 未连接，请先回到仪表盘建立连接",
            )
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

    /** 输出回调（由 TerminalView 组件注入到 TerminalClient）。 */
    fun setOutputCallback(callback: (ByteArray) -> Unit) {
        terminalClient.onOutput = callback
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
    )
}

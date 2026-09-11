package com.susumonitor.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.CommandRun
import com.susumonitor.data.model.CommandStatusValues
import com.susumonitor.data.repository.CommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 命令详情 UI 状态。 */
data class CommandRunDetailUiState(
    val isLoading: Boolean = true,
    val run: CommandRun? = null,
    val errorMessage: String? = null,
    val notEnabled: Boolean = false,
    /** 审批/驳回请求进行中。 */
    val isActing: Boolean = false,
    /** 批准后轮询执行结果中。 */
    val isPolling: Boolean = false,
    /** 审批动作的结果提示（如状态冲突、Agent 离线）。 */
    val actionMessage: String? = null,
)

/**
 * 命令详情 ViewModel：加载 / 审批 / 驳回 / 批准后轮询执行结果。
 * 轮询间隔 2s、上限 60s，进入终态或超时自动停止（后端无结果推送）。
 */
@HiltViewModel
class CommandRunDetailViewModel @Inject constructor(
    private val commandRepository: CommandRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommandRunDetailUiState())
    val uiState: StateFlow<CommandRunDetailUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    fun load(runId: Long) {
        if (_uiState.value.run?.id == runId) return
        viewModelScope.launch {
            _uiState.value = CommandRunDetailUiState(isLoading = true)
            try {
                val run = commandRepository.getRun(runId)
                _uiState.value = _uiState.value.copy(isLoading = false, run = run, notEnabled = false)
                if (run.status == CommandStatusValues.APPROVED ||
                    run.status == CommandStatusValues.EXECUTING
                ) {
                    startPolling(runId)
                }
            } catch (e: Exception) {
                val notEnabled = e is CommandRepository.CommandDomainNotEnabledException
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notEnabled = notEnabled,
                    errorMessage = if (notEnabled) "命令执行功能未启用" else ApiException.from(e).message,
                )
            }
        }
    }

    /** 批准并下发；成功后进入轮询。 */
    fun approve() {
        act { runId -> commandRepository.approve(runId) }
    }

    /** 驳回待审批命令。 */
    fun reject() {
        act { runId -> commandRepository.reject(runId) }
    }

    private fun act(action: suspend (Long) -> CommandRun) {
        val run = _uiState.value.run ?: return
        if (_uiState.value.isActing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isActing = true, actionMessage = null)
            try {
                val updated = action(run.id)
                _uiState.value = _uiState.value.copy(isActing = false, run = updated)
                if (updated.status == CommandStatusValues.APPROVED ||
                    updated.status == CommandStatusValues.EXECUTING
                ) {
                    startPolling(updated.id)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isActing = false,
                    actionMessage = actionErrorMessage(e),
                )
                // 状态冲突（40905）时刷新一次，呈现服务端最新状态
                val businessCode = (ApiException.from(e) as? ApiException.Business)?.code
                if (businessCode == com.susumonitor.util.ErrorCodes.COMMAND_RUN_STATE_CONFLICT) {
                    runCatching { commandRepository.getRun(run.id) }.getOrNull()?.let { latest ->
                        _uiState.value = _uiState.value.copy(run = latest)
                    }
                }
            }
        }
    }

    private fun actionErrorMessage(e: Exception): String {
        val api = ApiException.from(e)
        return when (api) {
            is ApiException.Business -> when (api.code) {
                com.susumonitor.util.ErrorCodes.COMMAND_RUN_STATE_CONFLICT -> "命令状态已变更（可能已被处理），已刷新最新状态"
                com.susumonitor.util.ErrorCodes.COMMAND_AGENT_OFFLINE -> "目标服务器 Agent 离线，无法下发命令"
                com.susumonitor.util.ErrorCodes.COMMAND_RATE_LIMIT_REACHED -> "命令提交过于频繁，请稍后再试"
                else -> api.message
            }
            else -> api.message ?: "操作失败"
        }
    }

    /** 批准后轮询执行结果：2s 一次，最长 60s，终态停止。 */
    private fun startPolling(runId: Long) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPolling = true)
            var waited = 0L
            while (waited < POLLING_TIMEOUT_MS) {
                delay(POLLING_INTERVAL_MS)
                waited += POLLING_INTERVAL_MS
                try {
                    val latest = commandRepository.getRun(runId)
                    _uiState.value = _uiState.value.copy(run = latest)
                    if (CommandStatusValues.isFinal(latest.status)) {
                        break
                    }
                } catch (e: Exception) {
                    // 轮询失败不中断：保留上一状态，等待下一轮
                }
            }
            _uiState.value = _uiState.value.copy(isPolling = false)
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POLLING_INTERVAL_MS = 2_000L
        const val POLLING_TIMEOUT_MS = 60_000L
    }
}

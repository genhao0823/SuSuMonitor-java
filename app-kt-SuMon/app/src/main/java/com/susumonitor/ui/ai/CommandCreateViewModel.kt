package com.susumonitor.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.CommandRun
import com.susumonitor.data.model.CommandStatusValues
import com.susumonitor.data.model.CommandTemplate
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.repository.CommandRepository
import com.susumonitor.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 发起页 Tab。 */
enum class CommandCreateTab { AI_SUGGESTION, MANUAL_TEMPLATE }

/** 命令发起 UI 状态。 */
data class CommandCreateUiState(
    /** 预选服务器（从服务器详情进入时）；0 表示需在页面选择。 */
    val serverId: Long = 0,
    val servers: List<Server> = emptyList(),
    val templates: List<CommandTemplate> = emptyList(),
    val tab: CommandCreateTab = CommandCreateTab.AI_SUGGESTION,
    // AI 建议
    val intent: String = "",
    /** AI 建议生成的待审批记录。 */
    val suggestedRuns: List<CommandRun> = emptyList(),
    // 手动模板
    val selectedTemplateId: String? = null,
    val params: Map<String, String> = emptyMap(),
    /** 本地正则校验失败提示。 */
    val paramError: String? = null,
    // 通用
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val notEnabled: Boolean = false,
)

/**
 * 命令发起 ViewModel：AI 意图生成待审批命令 + 手动按白名单模板发起。
 */
@HiltViewModel
class CommandCreateViewModel @Inject constructor(
    private val commandRepository: CommandRepository,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommandCreateUiState())
    val uiState: StateFlow<CommandCreateUiState> = _uiState.asStateFlow()

    /** 从路由参数初始化。 */
    fun init(serverId: Long) {
        if (_uiState.value.serverId == 0L) {
            _uiState.value = _uiState.value.copy(serverId = serverId)
            loadTemplates()
            if (serverId <= 0) loadServers()
        }
    }

    fun selectTab(tab: CommandCreateTab) {
        _uiState.value = _uiState.value.copy(tab = tab, errorMessage = null)
    }

    fun selectServer(serverId: Long) {
        _uiState.value = _uiState.value.copy(serverId = serverId)
    }

    fun updateIntent(value: String) {
        _uiState.value = _uiState.value.copy(intent = value)
    }

    fun selectTemplate(templateId: String) {
        _uiState.value = _uiState.value.copy(
            selectedTemplateId = templateId,
            params = emptyMap(),
            paramError = null,
        )
    }

    fun updateParam(name: String, value: String) {
        _uiState.value = _uiState.value.copy(params = _uiState.value.params + (name to value))
    }

    /** AI 建议：意图 → 待审批命令列表（自动审批策略开启时部分命令可能直接进入执行）。 */
    fun submitSuggestion() {
        val state = _uiState.value
        val intent = state.intent.trim()
        if (intent.isEmpty() || state.isSubmitting || state.serverId <= 0) return
        viewModelScope.launch {
            _uiState.value = state.copy(isSubmitting = true, errorMessage = null, notEnabled = false)
            try {
                val runs = commandRepository.suggest(state.serverId, intent)
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    suggestedRuns = runs,
                    errorMessage = if (runs.isEmpty()) {
                        "AI 未生成有效命令建议（可能全部被安全校验拒绝），请调整意图描述"
                    } else {
                        null
                    },
                )
                // 自动审批下发的命令已在执行，对建议列表轮询跟进到终态。
                startSuggestedPolling()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /**
     * 建议结果轮询：2s 一次，最长 60s；每轮拉取全部非终态建议行就地更新。
     * 轮询失败不中断（保留上一状态），全部到达终态自动停止。
     */
    private fun startSuggestedPolling() {
        suggestedPollingJob?.cancel()
        val initial = _uiState.value.suggestedRuns
        if (initial.none { !CommandStatusValues.isFinal(it.status) }) return
        suggestedPollingJob = viewModelScope.launch {
            var waited = 0L
            while (waited < POLLING_TIMEOUT_MS) {
                delay(POLLING_INTERVAL_MS)
                waited += POLLING_INTERVAL_MS
                val active = _uiState.value.suggestedRuns
                    .filter { !CommandStatusValues.isFinal(it.status) }
                if (active.isEmpty()) break
                val updated = active.mapNotNull { run ->
                    runCatching { commandRepository.getRun(run.id) }.getOrNull()
                }
                if (updated.isNotEmpty()) {
                    val merged = _uiState.value.suggestedRuns.map { current ->
                        updated.firstOrNull { it.id == current.id } ?: current
                    }
                    _uiState.value = _uiState.value.copy(suggestedRuns = merged)
                }
                if (_uiState.value.suggestedRuns.none { !CommandStatusValues.isFinal(it.status) }) {
                    break
                }
            }
        }
    }

    override fun onCleared() {
        suggestedPollingJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POLLING_INTERVAL_MS = 2_000L
        const val POLLING_TIMEOUT_MS = 60_000L
    }

    private var suggestedPollingJob: Job? = null

    /** 手动按模板发起；先本地正则预校验再提交。 */
    fun submitManual(onCreated: (Long) -> Unit) {
        val state = _uiState.value
        val template = state.templates.firstOrNull { it.id == state.selectedTemplateId }
        if (template == null || state.isSubmitting || state.serverId <= 0) return
        val invalidParam = commandRepository.validateParams(template, state.params)
        if (invalidParam != null) {
            _uiState.value = state.copy(paramError = "参数「$invalidParam」不符合格式要求")
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isSubmitting = true, errorMessage = null, paramError = null)
            try {
                val run = commandRepository.createManual(state.serverId, template.id, state.params)
                _uiState.value = _uiState.value.copy(isSubmitting = false)
                onCreated(run.id)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    private fun handleError(e: Exception) {
        val notEnabled = e is CommandRepository.CommandDomainNotEnabledException
        val message = when (e) {
            is CommandRepository.CommandDomainNotEnabledException -> e.message ?: "命令执行功能未启用"
            is CommandRepository.CommandRateLimitedException -> e.message ?: "命令提交过于频繁"
            else -> ApiException.from(e).message
        }
        _uiState.value = _uiState.value.copy(
            isSubmitting = false,
            notEnabled = notEnabled,
            errorMessage = message,
        )
    }

    private fun loadTemplates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val templates = commandRepository.templates()
                _uiState.value = _uiState.value.copy(isLoading = false, templates = templates)
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

    private fun loadServers() {
        viewModelScope.launch {
            runCatching { serverRepository.list(ServerQuery(page = 1, pageSize = 100)) }
                .onSuccess { result -> _uiState.value = _uiState.value.copy(servers = result.items) }
        }
    }
}

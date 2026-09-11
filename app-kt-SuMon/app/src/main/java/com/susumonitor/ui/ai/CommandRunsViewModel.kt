package com.susumonitor.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.CommandRun
import com.susumonitor.data.model.CommandStatusValues
import com.susumonitor.data.repository.CommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 命令状态筛选选项（全部 + 8 个状态）。 */
data class CommandStatusFilter(val label: String, val value: String?)

/** 命令运行列表 UI 状态。 */
data class CommandRunsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val notEnabled: Boolean = false,
    val runs: List<CommandRun> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val statusFilter: String? = null,
    /** 按服务器过滤；null 表示全部。 */
    val serverIdFilter: Long? = null,
)

/**
 * 命令审批列表 ViewModel：状态/服务器过滤 + 分页 + 下拉刷新。
 */
@HiltViewModel
class CommandRunsViewModel @Inject constructor(
    private val commandRepository: CommandRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommandRunsUiState())
    val uiState: StateFlow<CommandRunsUiState> = _uiState.asStateFlow()

    val statusFilters: List<CommandStatusFilter> = buildList {
        add(CommandStatusFilter("全部", null))
        add(CommandStatusFilter("待审批", CommandStatusValues.PENDING_APPROVAL))
        add(CommandStatusFilter("已批准", CommandStatusValues.APPROVED))
        add(CommandStatusFilter("执行中", CommandStatusValues.EXECUTING))
        add(CommandStatusFilter("成功", CommandStatusValues.SUCCEEDED))
        add(CommandStatusFilter("失败", CommandStatusValues.FAILED))
        add(CommandStatusFilter("已驳回", CommandStatusValues.REJECTED))
        add(CommandStatusFilter("已过期", CommandStatusValues.EXPIRED))
        add(CommandStatusFilter("超时", CommandStatusValues.TIMEOUT))
    }

    fun selectStatus(status: String?) {
        _uiState.value = _uiState.value.copy(statusFilter = status, page = 1)
        loadPage(1, reset = true)
    }

    fun selectServer(serverId: Long?) {
        _uiState.value = _uiState.value.copy(serverIdFilter = serverId, page = 1)
        loadPage(1, reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        loadPage(state.page + 1, reset = false)
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
        loadPage(1, reset = true, fromPull = true)
    }

    private fun loadPage(page: Int, reset: Boolean, fromPull: Boolean = false) {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isLoading = !fromPull, isRefreshing = fromPull, errorMessage = null)
            try {
                val result = commandRepository.listRuns(
                    page = page,
                    pageSize = 20,
                    serverId = state.serverIdFilter,
                    status = state.statusFilter,
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    notEnabled = false,
                    runs = if (reset) result.items else _uiState.value.runs + result.items,
                    total = result.total,
                    page = page,
                    hasMore = (page * 20) < result.total,
                )
            } catch (e: Exception) {
                val notEnabled = e is CommandRepository.CommandDomainNotEnabledException
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    notEnabled = notEnabled,
                    errorMessage = if (notEnabled) "命令执行功能未启用" else ApiException.from(e).message,
                )
            }
        }
    }

    init {
        loadPage(1, reset = true)
    }
}

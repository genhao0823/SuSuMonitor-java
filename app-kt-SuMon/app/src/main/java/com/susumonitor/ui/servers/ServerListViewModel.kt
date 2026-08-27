package com.susumonitor.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.SessionStore
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.model.SshTestResult
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.service.MonitorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 服务器列表 UI 状态。 */
data class ServerListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val servers: List<Server> = emptyList(),
    val latestMetrics: Map<Long, Metrics> = emptyMap(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Long = 0,
    val hasMore: Boolean = false,
    val keyword: String = "",
    val sortBy: String = "id",
    val sortOrder: String = "asc",
    val isAdmin: Boolean = false,
    /** serverId → SSH 测试结果（测试完成短暂展示）。 */
    val sshTestResults: Map<Long, SshTestResult> = emptyMap(),
)

/**
 * 服务器列表 ViewModel：搜索、排序、分页、下拉刷新、删除、SSH 测试。
 * 搜索防抖由 UI 层 LaunchedEffect 负责（500ms 后调 [applyKeyword]）。
 */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    sessionStore: SessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage()
        viewModelScope.launch {
            sessionStore.session.collect { session ->
                _uiState.value = _uiState.value.copy(isAdmin = session?.user?.role == "admin")
            }
        }
        viewModelScope.launch {
            MonitorForegroundService.metricsUpdates.collect { update ->
                _uiState.value = _uiState.value.copy(
                    latestMetrics = _uiState.value.latestMetrics + (update.serverId to update.metrics),
                )
            }
        }
    }

    /** 关键词防抖后生效：更新状态并重拉第一页。 */
    fun applyKeyword(keyword: String) {
        if (_uiState.value.keyword == keyword) return
        _uiState.value = _uiState.value.copy(keyword = keyword)
        loadFirstPage()
    }

    fun onSortChange(sortBy: String, sortOrder: String) {
        if (_uiState.value.sortBy == sortBy && _uiState.value.sortOrder == sortOrder) return
        _uiState.value = _uiState.value.copy(sortBy = sortBy, sortOrder = sortOrder)
        loadFirstPage()
    }

    /** 加载第一页（搜索/排序变化时调用）。 */
    fun loadFirstPage() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, page = 1)
        loadPage(1, reset = true)
    }

    /** 下拉刷新：静默重拉当前第一页。 */
    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val page = fetchPage(1)
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    isLoading = false,
                    servers = page.items,
                    total = page.total,
                    hasMore = (1 * _uiState.value.pageSize) < page.total,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    /** 加载下一页（滚动到底部）。 */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        loadPage(state.page + 1, reset = false)
    }

    /** 删除服务器（admin）。 */
    fun deleteServer(id: Long) {
        viewModelScope.launch {
            try {
                serverRepository.delete(id)
                val remaining = _uiState.value.servers.filterNot { it.id == id }
                // 当前页删空则回退一页
                if (remaining.isEmpty() && _uiState.value.page > 1) {
                    loadPage(_uiState.value.page - 1, reset = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        servers = remaining,
                        total = (_uiState.value.total - 1).coerceAtLeast(0),
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = ApiException.from(e).message)
            }
        }
    }

    /** SSH 测试（admin）。 */
    fun testSsh(id: Long) {
        viewModelScope.launch {
            try {
                val result = serverRepository.testSsh(id)
                _uiState.value = _uiState.value.copy(
                    sshTestResults = _uiState.value.sshTestResults + (id to result),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = ApiException.from(e).message)
            }
        }
    }

    /** 清除错误提示。 */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun loadPage(page: Int, reset: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = fetchPage(page)
                val servers = if (reset) result.items else _uiState.value.servers + result.items
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    servers = servers,
                    total = result.total,
                    page = page,
                    hasMore = (page * _uiState.value.pageSize) < result.total,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    private suspend fun fetchPage(page: Int) = serverRepository.list(
        ServerQuery(
            page = page,
            pageSize = _uiState.value.pageSize,
            keyword = _uiState.value.keyword.trim().ifEmpty { null },
            sortBy = _uiState.value.sortBy,
            sortOrder = _uiState.value.sortOrder,
        ),
    )
}

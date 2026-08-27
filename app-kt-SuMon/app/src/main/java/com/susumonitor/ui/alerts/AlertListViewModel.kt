package com.susumonitor.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AlertPushPayload
import com.susumonitor.data.model.AlertRecord
import com.susumonitor.data.model.AlertRecordQuery
import com.susumonitor.data.model.AlertStatusValues
import com.susumonitor.data.repository.AlertRepository
import com.susumonitor.service.MonitorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 告警状态筛选选项。 */
enum class AlertFilter(val label: String, val value: String?) {
    ALL("全部", null),
    UNREAD("未读", AlertStatusValues.UNREAD),
    READ("已读", AlertStatusValues.READ),
    RESOLVED("已恢复", AlertStatusValues.RESOLVED),
}

/** 告警列表 UI 状态。 */
data class AlertListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val records: List<AlertRecord> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val filter: AlertFilter = AlertFilter.ALL,
    val hasMore: Boolean = false,
    /** WS 推送到达但未刷新的计数（横幅提示）。 */
    val pendingPushCount: Int = 0,
)

/**
 * 告警列表 ViewModel：分页 + 状态筛选 + 标记已读 + WS alert.push 增量提示。
 */
@HiltViewModel
class AlertListViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertListUiState())
    val uiState: StateFlow<AlertListUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage()
        collectPushAlerts()
    }

    fun loadFirstPage() {
        _uiState.value = _uiState.value.copy(filter = AlertFilter.ALL, page = 1)
        loadPage(1, AlertFilter.ALL, reset = true)
    }

    fun selectFilter(filter: AlertFilter) {
        _uiState.value = _uiState.value.copy(filter = filter, page = 1)
        loadPage(1, filter, reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        loadPage(state.page + 1, state.filter, reset = false)
    }

    /** 下拉刷新：保留当前筛选，静默重拉第一页。 */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        val state = _uiState.value
        _uiState.value = state.copy(isRefreshing = true, errorMessage = null)
        loadPage(1, state.filter, reset = true, fromPull = true)
    }

    /** 标记已读并更新本地状态。 */
    fun markRead(id: Long) {
        viewModelScope.launch {
            runCatching { alertRepository.markRead(id) }
            _uiState.value = _uiState.value.copy(
                records = _uiState.value.records.map { record ->
                    if (record.id == id && record.status == AlertStatusValues.UNREAD) {
                        record.copy(status = AlertStatusValues.READ)
                    } else record
                },
            )
        }
    }

    private fun loadPage(page: Int, filter: AlertFilter, reset: Boolean, fromPull: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !fromPull,
                isRefreshing = fromPull,
                errorMessage = null,
            )
            try {
                val result = alertRepository.listRecords(
                    AlertRecordQuery(page = page, pageSize = 20, status = filter.value),
                )
                val records = if (reset) result.items else _uiState.value.records + result.items
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    records = records,
                    total = result.total,
                    page = page,
                    hasMore = (page * 20) < result.total,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    /** WS 告警推送：仅累加计数（REST 是真源），由 UI 横幅触发刷新。 */
    private fun collectPushAlerts() {
        viewModelScope.launch {
            MonitorForegroundService.alertEvents.collect { _: AlertPushPayload ->
                val current = _uiState.value
                val filterAllows = current.filter == AlertFilter.ALL ||
                    (current.filter == AlertFilter.UNREAD)
                if (!filterAllows) return@collect
                _uiState.value = current.copy(pendingPushCount = current.pendingPushCount + 1)
            }
        }
    }

    /** 横幅已点击：清零计数并刷新列表。 */
    fun markPendingPushSeen() {
        _uiState.value = _uiState.value.copy(pendingPushCount = 0)
        loadFirstPage()
    }
}

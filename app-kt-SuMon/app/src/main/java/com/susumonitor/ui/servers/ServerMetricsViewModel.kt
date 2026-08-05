package com.susumonitor.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AlertRule
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.ServerStatus
import com.susumonitor.data.repository.AlertRepository
import com.susumonitor.data.repository.MetricsRepository
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.service.MonitorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** 时间范围选项。 */
enum class TimeRange(val label: String, val hours: Long) {
    H1("1 小时", 1),
    H6("6 小时", 6),
    H24("24 小时", 24),
    D7("7 天", 168),
}

/** 实时监控 UI 状态。 */
data class ServerMetricsUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val latest: Metrics? = null,
    val history: List<Metrics> = emptyList(),
    val serverStatus: ServerStatus? = null,
    val rules: List<AlertRule> = emptyList(),
    val timeRange: TimeRange = TimeRange.H24,
)

/**
 * 实时监控 ViewModel：最新指标 + 历史数据 + 告警规则（阈值线）+ WS 实时更新。
 */
@HiltViewModel
class ServerMetricsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metricsRepository: MetricsRepository,
    private val serverRepository: ServerRepository,
    private val alertRepository: AlertRepository,
) : ViewModel() {

    val serverId: Long = savedStateHandle.get<Long>("serverId") ?: 0L

    private val _uiState = MutableStateFlow(ServerMetricsUiState())
    val uiState: StateFlow<ServerMetricsUiState> = _uiState.asStateFlow()

    init {
        loadAll()
        // WS 实时指标更新
        viewModelScope.launch {
            MonitorForegroundService.metricsUpdates.collect { update ->
                if (update.serverId == serverId) {
                    _uiState.value = _uiState.value.copy(latest = update.metrics)
                }
            }
        }
        viewModelScope.launch {
            MonitorForegroundService.statusUpdates.collect { push ->
                if (push.serverId == serverId) {
                    _uiState.value = _uiState.value.copy(
                        serverStatus = ServerStatus(
                            serverId = push.serverId,
                            status = push.status,
                            agentStatus = push.agentStatus,
                            lastHeartbeatAt = push.lastHeartbeatAt,
                            checkedAt = "",
                        ),
                    )
                }
            }
        }
    }

    /** 并行加载最新/历史/状态/规则。 */
    fun loadAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val latest = runCatching { metricsRepository.latest(serverId) }.getOrNull()
                val history = loadHistory(_uiState.value.timeRange)
                val status = runCatching { serverRepository.status(serverId) }.getOrNull()
                val rules = runCatching { alertRepository.listRules() }.getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    latest = latest,
                    history = history,
                    serverStatus = status,
                    rules = rules,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    /** 切换时间范围并重拉历史。 */
    fun selectRange(range: TimeRange) {
        if (_uiState.value.timeRange == range) return
        _uiState.value = _uiState.value.copy(timeRange = range)
        viewModelScope.launch {
            try {
                val history = loadHistory(range)
                _uiState.value = _uiState.value.copy(history = history, errorMessage = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = ApiException.from(e).message)
            }
        }
    }

    private suspend fun loadHistory(range: TimeRange): List<Metrics> {
        val end = Instant.now()
        val start = end.minus(range.hours, ChronoUnit.HOURS)
        return metricsRepository.history(
            serverId,
            start.toString(),
            end.toString(),
        ).sortedBy { it.collectedAt }
    }
}

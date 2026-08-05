package com.susumonitor.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerStatus
import com.susumonitor.data.repository.MetricsRepository
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.service.MonitorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 服务器详情 UI 状态。 */
data class ServerDetailUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val server: Server? = null,
    val status: ServerStatus? = null,
    val latest: Metrics? = null,
)

/**
 * 服务器详情 ViewModel：服务器信息 + 状态快照 + 最新指标（含 WS 实时更新）。
 */
@HiltViewModel
class ServerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val metricsRepository: MetricsRepository,
) : ViewModel() {

    val serverId: Long = savedStateHandle.get<Long>("serverId") ?: 0L

    private val _uiState = MutableStateFlow(ServerDetailUiState())
    val uiState: StateFlow<ServerDetailUiState> = _uiState.asStateFlow()

    init {
        load()
        // 订阅该服务器的实时指标
        viewModelScope.launch {
            MonitorForegroundService.metricsUpdates.collect { update ->
                if (update.serverId == serverId) {
                    _uiState.value = _uiState.value.copy(latest = update.metrics)
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val server = serverRepository.get(serverId)
                val status = serverRepository.status(serverId)
                val latest = metricsRepository.latest(serverId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    server = server,
                    status = status,
                    latest = latest,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "加载失败",
                )
            }
        }
    }
}

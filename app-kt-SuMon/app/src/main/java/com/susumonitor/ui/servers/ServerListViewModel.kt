package com.susumonitor.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
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
    val errorMessage: String? = null,
    val servers: List<Server> = emptyList(),
    val latestMetrics: Map<Long, Metrics> = emptyMap(),
)

/**
 * 服务器列表 ViewModel：分页加载 + WS 实时指标更新。
 */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    init {
        loadServers()
        viewModelScope.launch {
            MonitorForegroundService.metricsUpdates.collect { update ->
                _uiState.value = _uiState.value.copy(
                    latestMetrics = _uiState.value.latestMetrics + (update.serverId to update.metrics),
                )
            }
        }
    }

    fun loadServers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val page = serverRepository.list(ServerQuery(pageSize = 50))
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    servers = page.items,
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

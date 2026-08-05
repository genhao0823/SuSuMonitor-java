package com.susumonitor.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.service.MonitorForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 仪表盘 UI 状态。 */
data class DashboardUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val servers: List<Server> = emptyList(),
    /** serverId → 最新指标（来自 REST 初次加载 + WS 实时更新）。 */
    val latestMetrics: Map<Long, Metrics> = emptyMap(),
)

/**
 * 仪表盘 ViewModel：加载服务器列表，维护 WS 实时指标与状态推送。
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadServers()
        collectServiceEvents()
    }

    /** 加载服务器列表并启动监控服务订阅。 */
    fun loadServers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val page = serverRepository.list(ServerQuery(pageSize = 50))
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    servers = page.items,
                )
                // 订阅全部服务器到前台服务
                val serverIds = page.items.map { it.id }
                if (serverIds.isNotEmpty()) {
                    MonitorForegroundService.start(context, serverIds)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "加载失败",
                )
            }
        }
    }

    /** 收集前台服务的 WS 实时数据流。 */
    private fun collectServiceEvents() {
        viewModelScope.launch {
            MonitorForegroundService.metricsUpdates.collect { update ->
                _uiState.value = _uiState.value.copy(
                    latestMetrics = _uiState.value.latestMetrics + (update.serverId to update.metrics),
                )
            }
        }
        viewModelScope.launch {
            MonitorForegroundService.statusUpdates.collect { push ->
                val updatedServers = _uiState.value.servers.map { server ->
                    if (server.id == push.serverId) {
                        server.copy(
                            status = push.status,
                            agentStatus = push.agentStatus,
                            lastHeartbeatAt = push.lastHeartbeatAt,
                        )
                    } else server
                }
                _uiState.value = _uiState.value.copy(servers = updatedServers)
            }
        }
    }
}

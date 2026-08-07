package com.susumonitor.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.model.HealthStatus
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.ReadyStatus
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.repository.AdminRepository
import com.susumonitor.data.repository.AlertRepository
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.data.repository.SystemRepository
import com.susumonitor.data.model.ReviewStatusValues
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
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val servers: List<Server> = emptyList(),
    val latestMetrics: Map<Long, Metrics> = emptyMap(),
    // 探针
    val health: HealthStatus? = null,
    val healthError: String? = null,
    val ready: ReadyStatus? = null,
    val readyError: String? = null,
    // 分布
    val onlineCount: Int = 0,
    val offlineCount: Int = 0,
    val unknownCount: Int = 0,
    // 告警概览
    val unreadAlertCount: Long = 0,
    // admin
    val pendingUserCount: Long = 0,
)

/**
 * 仪表盘 ViewModel：服务器卡片 + 探针 + 分布 + 未读告警 + admin 入口 + WS 实时。
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverRepository: ServerRepository,
    private val systemRepository: SystemRepository,
    private val alertRepository: AlertRepository,
    private val adminRepository: AdminRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadServers()
        loadOverview()
        collectServiceEvents()
    }

    /** 加载服务器列表并启动监控服务订阅；下拉刷新时同时清掉刷新标记。 */
    fun loadServers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val page = serverRepository.list(ServerQuery(pageSize = 50))
                val servers = page.items
                val online = servers.count { it.status == "online" }
                val offline = servers.count { it.status == "offline" }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    servers = servers,
                    onlineCount = online,
                    offlineCount = offline,
                    unknownCount = servers.size - online - offline,
                )
                val serverIds = servers.map { it.id }
                if (serverIds.isNotEmpty()) {
                    MonitorForegroundService.start(context, serverIds)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = com.susumonitor.data.ApiException.from(e).message,
                )
            }
        }
    }

    /** 下拉刷新：重拉服务器列表 + 探针概览（列表加载完成时清除刷新标记）。 */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
        loadServers()
        loadOverview()
    }

    /** 加载健康/就绪探针 + 未读告警计数 + admin 待审核计数。 */
    fun loadOverview() {
        viewModelScope.launch {
            runCatching { systemRepository.health() }
                .onSuccess { health ->
                    _uiState.value = _uiState.value.copy(health = health)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(healthError = e.message)
                }
        }
        viewModelScope.launch {
            runCatching { systemRepository.ready() }
                .onSuccess { ready ->
                    _uiState.value = _uiState.value.copy(ready = ready)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(readyError = e.message)
                }
        }
        viewModelScope.launch {
            runCatching {
                alertRepository.listRecords(
                    com.susumonitor.data.model.AlertRecordQuery(
                        status = com.susumonitor.data.model.AlertStatusValues.UNREAD,
                        pageSize = 1,
                    ),
                ).total
            }.onSuccess { count ->
                _uiState.value = _uiState.value.copy(unreadAlertCount = count)
            }
        }
        viewModelScope.launch {
            runCatching {
                adminRepository.listUsers(
                    com.susumonitor.data.model.AdminUserQuery(
                        status = ReviewStatusValues.PENDING,
                        pageSize = 1,
                    ),
                ).total
            }.onSuccess { count ->
                _uiState.value = _uiState.value.copy(pendingUserCount = count)
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

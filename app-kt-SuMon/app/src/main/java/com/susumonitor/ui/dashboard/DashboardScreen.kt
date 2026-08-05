package com.susumonitor.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.components.ServerStatusCard

/**
 * 仪表盘：服务器状态卡片列表 + WS 实时指标刷新。
 * @param onServerClick 点击服务器卡片 → 详情页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onServerClick: (Long) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("SuSuMonitor 仪表盘") }) },
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.servers.isEmpty() -> LoadingState(Modifier.padding(innerPadding))
            uiState.errorMessage != null && uiState.servers.isEmpty() ->
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.loadServers() },
                    modifier = Modifier.padding(innerPadding),
                )
            uiState.servers.isEmpty() ->
                EmptyState(
                    text = "暂无监控服务器",
                    modifier = Modifier.padding(innerPadding),
                )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.servers, key = { it.id }) { server ->
                    ServerStatusCard(
                        serverName = server.name,
                        host = server.host,
                        status = server.status,
                        agentStatus = server.agentStatus,
                        lastHeartbeatAt = server.lastHeartbeatAt,
                        latest = uiState.latestMetrics[server.id],
                        onClick = { onServerClick(server.id) },
                    )
                }
            }
        }
    }
}

package com.susumonitor.ui.servers

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
 * 服务器列表页。
 * @param onServerClick 点击 → 详情
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onServerClick: (Long) -> Unit,
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("服务器") }) },
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.servers.isEmpty() -> LoadingState(Modifier.padding(innerPadding))
            uiState.errorMessage != null && uiState.servers.isEmpty() ->
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.loadServers() },
                    modifier = Modifier.padding(innerPadding),
                )
            uiState.servers.isEmpty() -> EmptyState(
                text = "暂无服务器",
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

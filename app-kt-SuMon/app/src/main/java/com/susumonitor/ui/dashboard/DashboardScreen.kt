package com.susumonitor.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * 仪表盘：探针卡 + 服务器概览卡 + 服务器卡片列表（WS 实时刷新）。
 * @param isAdmin 控制 admin 入口卡显示
 * @param onViewAlerts 跳转告警列表
 * @param onViewAdmin 跳转用户审核
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    isAdmin: Boolean,
    onServerClick: (Long) -> Unit = {},
    onViewAlerts: () -> Unit = {},
    onViewAdmin: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("SuSuMonitor 仪表盘") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 探针卡：健康/就绪
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProbeCard(
                        label = "后端",
                        ok = uiState.health?.status == "UP",
                        detail = uiState.health?.application ?: uiState.healthError ?: "-",
                        modifier = Modifier.weight(1f),
                    )
                    ProbeCard(
                        label = "就绪",
                        ok = uiState.ready?.status == "UP",
                        detail = uiState.ready?.database ?: uiState.readyError ?: "-",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 概览卡：服务器分布 + 未读告警 + admin 待审核
            item {
                OverviewCard(
                    online = uiState.onlineCount,
                    offline = uiState.offlineCount,
                    unknown = uiState.unknownCount,
                    unreadAlerts = uiState.unreadAlertCount,
                    pendingUsers = if (isAdmin) uiState.pendingUserCount else null,
                    onViewAlerts = onViewAlerts,
                    onViewAdmin = onViewAdmin,
                )
            }

            // 服务器卡片列表
            when {
                uiState.isLoading && uiState.servers.isEmpty() ->
                    item { LoadingState() }
                uiState.errorMessage != null && uiState.servers.isEmpty() ->
                    item {
                        ErrorState(
                            message = uiState.errorMessage.orEmpty(),
                            onRetry = { viewModel.loadServers() },
                        )
                    }
                uiState.servers.isEmpty() ->
                    item { EmptyState("暂无监控服务器") }
                else -> items(uiState.servers, key = { it.id }) { server ->
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

@Composable
private fun ProbeCard(
    label: String,
    ok: Boolean,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "$label ${if (ok) "●" else "○"}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = detail.take(20),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OverviewCard(
    online: Int,
    offline: Int,
    unknown: Int,
    unreadAlerts: Long,
    pendingUsers: Long?,
    onViewAlerts: () -> Unit,
    onViewAdmin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "运行概览", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("在线", online.toString())
                StatItem("离线", offline.toString())
                StatItem("未知", unknown.toString())
                StatItem("未读告警", unreadAlerts.toString())
                pendingUsers?.let {
                    StatItem("待审核", it.toString())
                }
            }
            Row {
                androidx.compose.material3.TextButton(onClick = onViewAlerts) { Text("查看告警记录") }
                if (pendingUsers != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.TextButton(onClick = onViewAdmin) { Text("用户审核") }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

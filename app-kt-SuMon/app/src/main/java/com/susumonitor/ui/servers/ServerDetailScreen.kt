package com.susumonitor.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.susumonitor.data.model.Metrics
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.util.TimeFormatter
import com.susumonitor.util.ValueFormatter

/**
 * 服务器详情页：基本信息 + 最新指标数值卡。
 * @param serverId 服务器 ID
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    serverId: Long,
    onBack: () -> Unit,
    viewModel: ServerDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.server?.name ?: "服务器详情") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Text("←")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.server == null -> LoadingState(Modifier.padding(innerPadding))
            uiState.errorMessage != null && uiState.server == null ->
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.load() },
                    modifier = Modifier.padding(innerPadding),
                )
            uiState.server != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ServerInfoCard(uiState)
                MetricsGrid(uiState.latest)
            }
        }
    }
}

@Composable
private fun ServerInfoCard(uiState: ServerDetailUiState) {
    val server = uiState.server ?: return
    val status = uiState.status
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(label = "主机", value = server.host)
            InfoRow(label = "SSH", value = "${server.sshUser}@${server.sshHost}:${server.sshPort}")
            InfoRow(label = "状态", value = statusText(server.status))
            InfoRow(label = "Agent", value = agentText(server.agentStatus))
            InfoRow(label = "最近心跳", value = TimeFormatter.formatLocal(status?.lastHeartbeatAt))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MetricsGrid(latest: Metrics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "实时指标", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            if (latest == null) {
                Text(
                    text = "暂无指标数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "采集时间 ${TimeFormatter.formatLocal(latest.collectedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricRow(
                    cpu = latest.cpuPercent,
                    memory = latest.memoryPercent,
                    disk = latest.diskPercent,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricItem("网络 RX", ValueFormatter.bytes(latest.netRx), Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricItem("网络 TX", ValueFormatter.bytes(latest.netTx), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricItem("温度", ValueFormatter.metricValue("temperature", latest.temperature), Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricItem("负载", ValueFormatter.metricValue("load", latest.loadAvg), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricRow(cpu: Double?, memory: Double?, disk: Double?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        MetricItem("CPU", ValueFormatter.percent(cpu), Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        MetricItem("内存", ValueFormatter.percent(memory), Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        MetricItem("磁盘", ValueFormatter.percent(disk), Modifier.weight(1f))
    }
}

@Composable
private fun MetricItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleSmall)
    }
}

private fun statusText(status: String): String = when (status) {
    "online" -> "在线"
    "offline" -> "离线"
    else -> status
}

private fun agentText(agentStatus: String): String = when (agentStatus) {
    "online" -> "在线"
    "offline" -> "离线"
    else -> agentStatus
}

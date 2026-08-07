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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LineSpec
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.components.MetricsLineChart
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter
import com.susumonitor.util.ValueFormatter

/**
 * 实时监控页：指标卡 + 历史折线图（CPU/内存/磁盘 百分比 + 网络 I/O）+ 时间选择器 + 阈值线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerMetricsScreen(
    serverId: Long,
    onBack: () -> Unit,
    viewModel: ServerMetricsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时监控") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        // 下拉刷新：重拉最新/历史/状态/规则
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // 时间范围选择器
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = uiState.timeRange == range,
                        onClick = { viewModel.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }

            // 最新指标卡
            LatestMetricsCards(uiState.latest)

            // 连接状态
            Row {
                Text(
                    text = "WS ${if (uiState.serverStatus?.agentStatus == "online") "已连接" else "未连接"} · Agent ${uiState.serverStatus?.agentStatus ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 百分比图（CPU/内存/磁盘，固定 0-100 + 阈值线）
            MetricsLineChart(
                data = uiState.history,
                lines = listOf(
                    LineSpec("cpu_percent", Color(0xFF9C7BD8), "CPU"),
                    LineSpec("memory_percent", Color(0xFF4CAF7D), "内存"),
                    LineSpec("disk_percent", Color(0xFF42A5F5), "磁盘"),
                ),
                rules = uiState.rules,
                fixedMax = 100.0,
            )

            // 网络 I/O 图（字节，自适应）
            MetricsLineChart(
                data = uiState.history,
                lines = listOf(
                    LineSpec("net_rx", Color(0xFFF5A623), "RX"),
                    LineSpec("net_tx", Color(0xFFE5484D), "TX"),
                ),
            )

            if (uiState.isLoading) {
                LoadingState(modifier = Modifier.fillMaxWidth())
            }
            if (uiState.errorMessage != null) {
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.loadAll() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "采样 ${uiState.history.size} 条 · 最新 ${uiState.latest?.let { TimeFormatter.formatLocal(it.collectedAt) } ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
}

@Composable
private fun LatestMetricsCards(latest: com.susumonitor.data.model.Metrics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "最新指标", style = MaterialTheme.typography.titleMedium)
            if (latest == null) {
                Text("暂无数据", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricBox("CPU", ValueFormatter.percent(latest.cpuPercent), StatusOnline, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    MetricBox("内存", ValueFormatter.percent(latest.memoryPercent), StatusOnline, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    MetricBox("磁盘", ValueFormatter.percent(latest.diskPercent), StatusOnline, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricBox("RX", ValueFormatter.bytes(latest.netRx), StatusWarning, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    MetricBox("TX", ValueFormatter.bytes(latest.netTx), StatusWarning, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    MetricBox("负载", ValueFormatter.metricValue("load", latest.loadAvg), StatusCritical, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = accent)
    }
}

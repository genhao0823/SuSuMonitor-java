package com.susumonitor.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.AlertRecord
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter
import com.susumonitor.util.ValueFormatter

/**
 * 告警列表页：状态筛选 Tabs + 分页列表 + 标记已读 + WS 推送横幅 + 规则入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertListScreen(
    onViewRules: () -> Unit = {},
    viewModel: AlertListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("告警记录") },
                actions = {
                    androidx.compose.material3.TextButton(onClick = onViewRules) { Text("规则") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // WS 推送横幅
            if (uiState.pendingPushCount > 0) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "有 ${uiState.pendingPushCount} 条新告警到达，点此刷新",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.markPendingPushSeen() }
                            .padding(12.dp),
                    )
                }
            }
            TabRow(selectedTabIndex = selectedTab) {
                AlertFilter.entries.forEachIndexed { index, filter ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            viewModel.selectFilter(filter)
                        },
                        text = { Text(filter.label) },
                    )
                }
            }

            when {
                uiState.isLoading && uiState.records.isEmpty() -> LoadingState()
                uiState.errorMessage != null && uiState.records.isEmpty() ->
                    ErrorState(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = { viewModel.loadFirstPage() },
                    )
                uiState.records.isEmpty() -> EmptyState(text = "暂无告警记录")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(uiState.records, key = { it.id }) { record ->
                        AlertRecordCard(
                            record = record,
                            onClick = { viewModel.markRead(record.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 单条告警记录卡片；点击未读记录标记已读。 */
@Composable
private fun AlertRecordCard(
    record: AlertRecord,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // 等级色条：critical 红 / warning 橙
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(52.dp)
                    .background(
                        color = if (record.level == "critical") StatusCritical else StatusWarning,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = levelLabel(record.level),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (record.level == "critical") StatusCritical else StatusWarning,
                    )
                    Text(
                        text = "  ${ValueFormatter.metricLabel(record.metric)} · ${serverLabel(record)}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "当前 ${ValueFormatter.formatMetric(record.metric, record.currentValue)} / 阈值 ${ValueFormatter.formatMetric(record.metric, record.thresholdValue)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = TimeFormatter.formatLocal(record.triggeredAt) + statusSuffix(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                record.notifyChannels?.takeIf { it.isNotBlank() }?.let { channels ->
                    Text(
                        text = "通知送达：${channels.split(",").joinToString("/") { channelLabel(it) }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun levelLabel(level: String): String = when (level) {
    "critical" -> "严重"
    else -> "警告"
}

private fun channelLabel(channel: String): String = when (channel.trim()) {
    "email" -> "邮件"
    "dingtalk" -> "钉钉"
    "webhook" -> "Webhook"
    else -> channel.trim()
}

private fun serverLabel(record: AlertRecord): String = "服务器 #${record.serverId}"

private fun statusSuffix(record: AlertRecord): String = when (record.status) {
    "unread" -> " · 未读"
    "read" -> " · 已读"
    "resolved" -> " · 已恢复"
    else -> ""
}

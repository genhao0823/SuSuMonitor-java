package com.susumonitor.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.CommandApprovalModeValues
import com.susumonitor.data.model.CommandRun
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.util.TimeFormatter

/**
 * 命令审批列表页（M1）：状态过滤 + 服务器过滤 + 分页列表。
 * 点击进入详情审批；右上角发起新命令 / 配置自动审批策略。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandRunsScreen(
    onBack: () -> Unit = {},
    onOpenDetail: (Long) -> Unit = {},
    onCreate: (() -> Unit)? = null,
    onOpenPolicy: (() -> Unit)? = null,
    viewModel: CommandRunsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("命令审批") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
                actions = {
                    if (onOpenPolicy != null) {
                        TextButton(onClick = onOpenPolicy) { Text("策略") }
                    }
                    if (onCreate != null) {
                        TextButton(onClick = onCreate) { Text("发起") }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 状态过滤 chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.statusFilters.forEach { filter ->
                    val selected = uiState.statusFilter == filter.value
                    AssistChip(
                        onClick = { viewModel.selectStatus(filter.value) },
                        label = { Text(filter.label) },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    uiState.notEnabled -> EmptyState(text = "命令执行功能未在后端启用")
                    uiState.isLoading && uiState.runs.isEmpty() -> LoadingState()
                    uiState.errorMessage != null && uiState.runs.isEmpty() ->
                        ErrorState(
                            message = uiState.errorMessage.orEmpty(),
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    uiState.runs.isEmpty() -> EmptyState(text = "暂无命令记录")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.runs, key = { it.id }) { run ->
                            CommandRunCard(run = run, onClick = { onOpenDetail(run.id) })
                        }
                    }
                }
            }
        }
    }
}

/** 命令记录卡片：渲染命令等宽预览 + 状态/来源/时间。 */
@Composable
private fun CommandRunCard(run: CommandRun, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CommandStatusBadge(run.status)
                    if (run.approvalMode == CommandApprovalModeValues.AUTO) {
                        CommandAutoApprovalBadge()
                    }
                    Text(
                        text = "  ${commandSourceLabel(run.source)} · ${commandRiskLabel(run.riskLevel)} · 服务器 #${run.serverId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = run.templateId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = run.renderedCommand,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = TimeFormatter.formatLocal(run.createdAt) + expirySuffix(run),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun expirySuffix(run: CommandRun): String =
    if (run.status == "pending_approval" && run.expiresAt != null) {
        " · 审批截止 ${TimeFormatter.formatLocal(run.expiresAt)}"
    } else {
        ""
    }

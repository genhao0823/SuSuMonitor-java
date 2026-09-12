package com.susumonitor.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.AiHealthReport
import com.susumonitor.data.model.AiHealthReportStatusValues
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter

/**
 * AI 定时健康报告列表页（F3）：报告卡片流 + 下拉刷新 + 滚动分页 + 手动生成。
 * 点击卡片进入详情；右上角"生成"弹确认框（可选日期，留空由后端取昨日）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthReportsScreen(
    onBack: () -> Unit = {},
    onOpenDetail: (Long) -> Unit = {},
    viewModel: HealthReportsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showGenerateDialog by remember { mutableStateOf(false) }

    // 生成成功后自动导航到新报告详情（一次性消费，返回列表不重复导航）
    uiState.generatedReportId?.let { reportId ->
        LaunchedEffect(reportId) {
            onOpenDetail(reportId)
            viewModel.consumeGeneratedReport()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("健康报告") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
                actions = {
                    TextButton(
                        onClick = { showGenerateDialog = true },
                        enabled = !uiState.isGenerating,
                    ) { Text(if (uiState.isGenerating) "生成中…" else "生成") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    uiState.notEnabled -> EmptyState(text = "健康报告功能未在后端启用")
                    uiState.isLoading && uiState.reports.isEmpty() -> LoadingState()
                    uiState.errorMessage != null && uiState.reports.isEmpty() ->
                        ErrorState(
                            message = uiState.errorMessage.orEmpty(),
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    uiState.reports.isEmpty() -> EmptyState(
                        text = "暂无报告；点击右上角「生成」可立即生成一份",
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.reports, key = { it.id }) { report ->
                            HealthReportCard(
                                report = report,
                                onClick = { onOpenDetail(report.id) },
                            )
                        }
                        // 加载更多：滚到底触发（与 ServerListScreen 同机制）
                        if (uiState.hasMore) {
                            item {
                                LaunchedEffect(uiState.page) {
                                    viewModel.loadMore()
                                }
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showGenerateDialog) {
        GenerateReportDialog(
            isGenerating = uiState.isGenerating,
            onDismiss = { showGenerateDialog = false },
            onConfirm = { dateText ->
                showGenerateDialog = false
                viewModel.generate(dateText.takeIf { it.isNotBlank() })
            },
        )
    }
}

/** 手动生成确认框：可选日期输入（YYYY-MM-DD），留空由后端取昨日。 */
@Composable
private fun GenerateReportDialog(
    isGenerating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var dateText by remember { mutableStateOf("") }
    val dateValid = dateText.isBlank() || DATE_PATTERN.matches(dateText)

    AlertDialog(
        onDismissRequest = { if (!isGenerating) onDismiss() },
        title = { Text("手动生成报告") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将聚合监控数据并调用模型生成报告，可能需要数十秒。同一日期重复生成将覆盖旧报告。")
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("报告日期(留空则生成昨日)") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    isError = !dateValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        if (!dateValid) Text("日期格式应为 YYYY-MM-DD")
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dateText.trim()) }, enabled = dateValid && !isGenerating) {
                Text("生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isGenerating) { Text("取消") }
        },
    )
}

/** 报告卡片：日期 + 状态徽标 + 摘要首行 + 用量/耗时摘要。 */
@Composable
private fun HealthReportCard(report: AiHealthReport, onClick: () -> Unit) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReportStatusBadge(report.status, report.errorCode)
                    Text(
                        text = "  ${report.model.ifBlank { "无模型摘要" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = report.reportDate,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = report.summary?.lines()?.firstOrNull { it.isNotBlank() }
                    ?: "模型摘要缺席，仅含聚合事实",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "生成于 " + TimeFormatter.formatLocal(report.createdAt) +
                    " · token ${report.usage?.totalTokens ?: 0} · ${report.durationMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 状态徽标：succeeded=绿色"正常"；degraded=橙色"降级"，附错误码。 */
@Composable
private fun ReportStatusBadge(status: String, errorCode: Int?) {
    val container = if (status == AiHealthReportStatusValues.SUCCEEDED) StatusOnline else StatusWarning
    val label = if (status == AiHealthReportStatusValues.SUCCEEDED) {
        "正常"
    } else {
        if (errorCode != null) "降级($errorCode)" else "降级"
    }
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = container.copy(alpha = 0.16f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = container,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")

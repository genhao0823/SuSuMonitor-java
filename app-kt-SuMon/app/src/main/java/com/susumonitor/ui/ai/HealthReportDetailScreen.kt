package com.susumonitor.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.AiHealthReport
import com.susumonitor.data.model.AiHealthReportStatusValues
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter
import com.susumonitor.util.ValueFormatter

/**
 * AI 定时健康报告详情页（F3）：摘要/值得关注三件事/局限说明 + 聚合事实四分组 + 元信息。
 * 事实数据以服务端 facts 快照为准，模型文本仅作解读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthReportDetailScreen(
    reportId: Long,
    onBack: () -> Unit = {},
    viewModel: HealthReportDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(reportId) { viewModel.load(reportId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("健康报告详情") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        val report = uiState.report
        when {
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(innerPadding))
            uiState.notEnabled || report == null ->
                ErrorState(
                    message = uiState.errorMessage ?: "报告不存在或功能未启用",
                    onRetry = { viewModel.load(reportId) },
                    modifier = Modifier.padding(innerPadding),
                )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReportStatusCard(report)
                if (!report.summary.isNullOrBlank()) {
                    DetailSection("摘要") {
                        Text(
                            text = report.summary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (report.topConcerns.isNotEmpty()) {
                    DetailSection("值得关注") {
                        report.topConcerns.forEachIndexed { index, concern ->
                            Text(
                                text = "${index + 1}. $concern",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                report.facts?.let { facts ->
                    DetailSection("服务器清单") {
                        DetailRow("总数", facts.serverInventory.totalCount.toString())
                        DetailRow("在线", facts.serverInventory.onlineCount.toString())
                        DetailRow("离线", facts.serverInventory.offlineCount.toString())
                        DetailRow("在线率", ValueFormatter.percent(facts.serverInventory.onlineRate))
                    }
                    DetailSection("指标概览(窗口均值 / 峰值)") {
                        DetailRow(
                            "CPU",
                            "${ValueFormatter.percent(facts.metricPeaks.avgCpuPercent)} / " +
                                "${ValueFormatter.percent(facts.metricPeaks.maxCpuPercent)}" +
                                "（峰值 #${facts.metricPeaks.maxCpuServerId ?: "-"}）",
                        )
                        DetailRow(
                            "内存",
                            "${ValueFormatter.percent(facts.metricPeaks.avgMemoryPercent)} / " +
                                "${ValueFormatter.percent(facts.metricPeaks.maxMemoryPercent)}" +
                                "（峰值 #${facts.metricPeaks.maxMemoryServerId ?: "-"}）",
                        )
                        DetailRow(
                            "磁盘",
                            "${ValueFormatter.percent(facts.metricPeaks.avgDiskPercent)} / " +
                                "${ValueFormatter.percent(facts.metricPeaks.maxDiskPercent)}" +
                                "（峰值 #${facts.metricPeaks.maxDiskServerId ?: "-"}）",
                        )
                    }
                    DetailSection("告警统计") {
                        DetailRow("触发总数", facts.alertStatistics.totalTriggered.toString())
                        DetailRow(
                            "分级",
                            "critical ${facts.alertStatistics.criticalCount} / " +
                                "warning ${facts.alertStatistics.warningCount}",
                        )
                        DetailRow(
                            "恢复情况",
                            "已恢复 ${facts.alertStatistics.resolvedCount} / " +
                                "未恢复 ${facts.alertStatistics.unresolvedCount}",
                        )
                        facts.alertStatistics.topServers.forEach { server ->
                            DetailRow(
                                server.serverName,
                                "#${server.serverId} 共 ${server.alertCount} 条（critical ${server.criticalCount}）",
                            )
                        }
                    }
                    if (facts.offlineServers.isNotEmpty()) {
                        DetailSection("离线服务器(生成时刻快照)") {
                            facts.offlineServers.forEach { server ->
                                DetailRow(
                                    server.serverName,
                                    "#${server.serverId} · Agent ${server.agentStatus} · 心跳 " +
                                        TimeFormatter.formatLocal(server.lastHeartbeatAt),
                                )
                            }
                        }
                    }
                }
                if (report.limitations.isNotEmpty()) {
                    DetailSection("局限说明") {
                        report.limitations.forEach { limitation ->
                            Text(text = limitation, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                DetailSection("元信息") {
                    DetailRow("Provider", report.provider)
                    DetailRow("模型", report.model.ifBlank { "-" })
                    DetailRow("Prompt", report.promptVersion)
                    DetailRow("Token", (report.usage?.totalTokens ?: 0).toString())
                    DetailRow("生成耗时", "${report.durationMs} ms")
                    report.createdAt?.let { DetailRow("生成时间", TimeFormatter.formatLocal(it)) }
                }
            }
        }
    }
}

/** 状态卡：报告日期 + 正常/降级徽标，降级时附错误码与提示。 */
@Composable
private fun ReportStatusCard(report: AiHealthReport) {
    val degraded = report.status != AiHealthReportStatusValues.SUCCEEDED
    val color = if (degraded) StatusWarning else StatusOnline
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = report.reportDate,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (degraded) "降级" else "正常",
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (degraded) {
                Text(
                    text = "模型摘要不可用（错误码 ${report.errorCode ?: "-"}），以下仅含服务端聚合事实",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

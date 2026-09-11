package com.susumonitor.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.AiDiagnosis
import com.susumonitor.data.model.AiEvidence
import com.susumonitor.data.model.AiSeverityValues
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter

/** 时间窗快捷档（分钟）。 */
private val HISTORY_WINDOW_OPTIONS = listOf(30, 60, 180, 360, 1440)

/**
 * AI 只读诊断页：选择时间窗 + 提问 → 结构化诊断结果。
 * 入口：服务器详情页（admin）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDiagnosisScreen(
    serverId: Long,
    onBack: () -> Unit = {},
    viewModel: AiDiagnosisViewModel = hiltViewModel(),
) {
    LaunchedEffect(serverId) { viewModel.init(serverId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 健康诊断") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "目标服务器 #${uiState.serverId}",
                style = MaterialTheme.typography.titleSmall,
            )

            // 时间窗选择
            Text("分析时间窗", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HISTORY_WINDOW_OPTIONS.forEach { minutes ->
                    AssistChip(
                        onClick = { viewModel.selectHistoryMinutes(minutes) },
                        label = { Text(windowLabel(minutes)) },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = if (uiState.historyMinutes == minutes) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = uiState.question,
                onValueChange = viewModel::updateQuestion,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("描述你关注的性能问题，如：为什么最近一小时 CPU 持续偏高？") },
                minLines = 3,
                maxLines = 6,
                enabled = !uiState.isSubmitting,
            )

            Button(
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isSubmitting && uiState.question.isNotBlank(),
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (uiState.isSubmitting) "诊断中，可能需要数十秒…" else "开始诊断")
            }

            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            val result = uiState.result
            if (result != null) {
                DiagnosisResultCard(result)
            } else if (uiState.errorMessage == null && !uiState.isSubmitting) {
                EmptyState(
                    text = "提交问题后，AI 将基于所选时间窗的\n脱敏监控摘要给出只读分析",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DiagnosisResultCard(result: AiDiagnosis) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 结论 + 严重级别
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeverityBadge(result.severity)
                    Text(
                        text = "  诊断结论",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(text = result.summary, style = MaterialTheme.typography.bodyMedium)
                if (!result.modelUsed) {
                    Text(
                        text = "⚠ 模型暂不可用，以上为服务端确定性聚合摘要",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusWarning,
                    )
                }
                result.usage?.let { usage ->
                    Text(
                        text = "${result.provider}/${result.model} · tokens ${usage.totalTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard("发现的问题") {
            result.findings.forEach { finding ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = finding.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "  置信度 ${confidenceLabel(finding.confidence)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = finding.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard("指标证据") {
            result.evidence.forEach { evidence ->
                EvidenceRow(evidence)
            }
        }

        SectionCard("排查建议") {
            result.recommendations.forEachIndexed { index, recommendation ->
                Text(
                    text = "${index + 1}. $recommendation",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        SectionCard("诊断边界") {
            result.limitations.forEach { limitation ->
                Text(
                    text = "· $limitation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Column(modifier = Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

@Composable
private fun SeverityBadge(severity: String) {
    val (label, color) = when (severity) {
        AiSeverityValues.CRITICAL -> "严重" to StatusCritical
        AiSeverityValues.WARNING -> "警告" to StatusWarning
        AiSeverityValues.INFO -> "正常" to com.susumonitor.ui.theme.StatusOnline
        else -> "未知" to com.susumonitor.ui.theme.StatusOffline
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun EvidenceRow(evidence: AiEvidence) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${evidence.metric} @ ${TimeFormatter.formatLocal(evidence.observedAt)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = evidence.value?.toString() ?: "-",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun windowLabel(minutes: Int): String = when (minutes) {
    30 -> "30 分钟"
    60 -> "1 小时"
    180 -> "3 小时"
    360 -> "6 小时"
    1440 -> "24 小时"
    else -> "$minutes 分钟"
}

private fun confidenceLabel(confidence: String): String = when (confidence) {
    "high" -> "高"
    "medium" -> "中"
    "low" -> "低"
    else -> "未知"
}

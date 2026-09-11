package com.susumonitor.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.CommandApprovalModeValues
import com.susumonitor.data.model.CommandStatusValues
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.util.TimeFormatter

/**
 * 命令详情页：完整字段展示 + 待审批时批准/驳回 + 批准后轮询执行结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandRunDetailScreen(
    runId: Long,
    onBack: () -> Unit = {},
    viewModel: CommandRunDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(runId) { viewModel.load(runId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("命令详情") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        val run = uiState.run
        when {
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(innerPadding))
            uiState.notEnabled || run == null ->
                ErrorState(
                    message = uiState.errorMessage ?: "命令不存在或功能未启用",
                    onRetry = { viewModel.load(runId) },
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
                // 状态 + 审批方式 + 轮询提示
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    CommandStatusBadge(run.status)
                    CommandRiskBadge(run.riskLevel)
                    if (run.approvalMode == CommandApprovalModeValues.AUTO) {
                        CommandAutoApprovalBadge()
                    }
                    Text(
                        text = "${commandSourceLabel(run.source)} · 服务器 #${run.serverId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (uiState.isPolling) {
                    Text(
                        text = "命令执行中，正在获取结果…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                uiState.actionMessage?.let { message ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                // 待审批操作区
                if (run.status == CommandStatusValues.PENDING_APPROVAL) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            run.expiresAt?.let {
                                Text(
                                    text = "审批截止：${TimeFormatter.formatLocal(it)}，逾期自动作废",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.susumonitor.ui.theme.StatusWarning,
                                )
                            }
                            Text(
                                text = "批准后将立即下发到目标服务器执行，请确认命令内容",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = viewModel::approve,
                                    enabled = !uiState.isActing,
                                    modifier = Modifier.weight(1f),
                                ) { Text("批准执行") }
                                OutlinedButton(
                                    onClick = viewModel::reject,
                                    enabled = !uiState.isActing,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = StatusCritical,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) { Text("驳回") }
                            }
                        }
                    }
                }

                // 将执行 / 已执行的命令
                DetailSection("命令内容") {
                    Text(
                        text = run.renderedCommand,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(10.dp),
                    )
                    if (run.params.isNotEmpty()) {
                        Text(
                            text = "参数：" + run.params.entries.joinToString { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                // AI 建议上下文
                run.proposal?.let { proposal ->
                    DetailSection("AI 建议上下文") {
                        proposal.reason?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = listOfNotNull(proposal.model, proposal.promptVersion).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 执行结果
                run.result?.let { result ->
                    DetailSection("执行结果") {
                        result.stdout?.takeIf { it.isNotBlank() }?.let { stdout ->
                            ResultBlock("stdout", stdout)
                        }
                        result.stderr?.takeIf { it.isNotBlank() }?.let { stderr ->
                            ResultBlock("stderr", stderr, color = StatusCritical)
                        }
                        result.error?.takeIf { it.isNotBlank() }?.let { error ->
                            ResultBlock("error", error, color = StatusCritical)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            run.exitCode?.let { exitCode ->
                                Text(
                                    text = "退出码 $exitCode",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (exitCode == 0) StatusOnline else StatusCritical,
                                )
                            }
                            if (result.truncated == true) {
                                Text(
                                    text = "输出已截断",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 审计信息
                DetailSection("审计信息") {
                    DetailRow("记录 ID", run.id.toString())
                    DetailRow("模板", run.templateId)
                    DetailRow("执行标识", run.executionId)
                    DetailRow("发起人", "#${run.proposerId ?: "-"}")
                    run.approverId?.let { DetailRow("审批人", "#$it") }
                    DetailRow("创建时间", TimeFormatter.formatLocal(run.createdAt))
                    run.completedAt?.let { DetailRow("完成时间", TimeFormatter.formatLocal(it)) }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

@Composable
private fun ResultBlock(label: String, content: String, color: androidx.compose.ui.graphics.Color? = null) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color ?: MaterialTheme.colorScheme.primary,
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
        )
    }
}

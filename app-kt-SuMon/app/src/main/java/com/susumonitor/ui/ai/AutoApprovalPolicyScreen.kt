package com.susumonitor.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.util.TimeFormatter
import kotlinx.coroutines.delay

/**
 * 自动审批策略页：实例级开关 + 风险阈值。
 * 开启后 AI 建议的中低风险命令跳过人工审批直接下发；手动命令不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoApprovalPolicyScreen(
    onBack: () -> Unit = {},
    viewModel: AutoApprovalPolicyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 保存成功提示 3 秒后自动消失
    LaunchedEffect(uiState.savedMessage) {
        if (uiState.savedMessage != null) {
            delay(3_000)
            viewModel.consumeSavedMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动审批策略") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.notEnabled -> ErrorState(
                message = uiState.errorMessage ?: "命令执行功能未在后端启用",
                onRetry = viewModel::load,
                modifier = Modifier.padding(innerPadding),
            )
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(innerPadding))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 当前状态提示卡
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.enabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (uiState.enabled) {
                                "自动审批已开启：AI 建议的${if (uiState.maxRiskLevel == "low") "低风险" else "中低风险"}命令将直接下发执行"
                            } else {
                                "自动审批未开启：所有命令（含 AI 建议）均需人工审批"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "仅影响 AI 建议；仍限白名单只读命令，模板与参数校验、执行通道防线不变",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用自动审批", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = uiState.enabled, onCheckedChange = viewModel::setEnabled)
                }

                // 风险阈值
                Text("自动审批风险阈值", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.maxRiskLevel == "low",
                        onClick = { viewModel.setMaxRiskLevel("low") },
                        label = { Text("仅低风险") },
                        enabled = uiState.enabled,
                    )
                    FilterChip(
                        selected = uiState.maxRiskLevel == "medium",
                        onClick = { viewModel.setMaxRiskLevel("medium") },
                        label = { Text("中低风险") },
                        enabled = uiState.enabled,
                    )
                }

                uiState.updatedAt?.let {
                    Text(
                        text = "上次修改：${TimeFormatter.formatLocal(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = viewModel::save,
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (uiState.isSaving) "保存中…" else "保存策略") }

                uiState.savedMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                uiState.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = "说明：手动创建的命令始终需要人工审批；高风险命令永不自动执行。" +
                        "实例级总开关由服务端配置（AI_COMMAND_ENABLED）控制。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

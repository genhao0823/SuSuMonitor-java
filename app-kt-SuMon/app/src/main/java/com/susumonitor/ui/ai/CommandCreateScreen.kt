package com.susumonitor.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.CommandApprovalModeValues
import com.susumonitor.data.model.CommandTemplate
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.LoadingState

/**
 * 命令发起页：AI 意图建议 / 手动白名单模板双入口。
 * 生成的命令均为待审批状态，需在详情页批准后执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandCreateScreen(
    serverId: Long = 0,
    onBack: () -> Unit = {},
    onOpenDetail: (Long) -> Unit = {},
    viewModel: CommandCreateViewModel = hiltViewModel(),
) {
    LaunchedEffect(serverId) { viewModel.init(serverId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发起命令") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(
                selectedTabIndex = uiState.tab.ordinal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = uiState.tab == CommandCreateTab.AI_SUGGESTION,
                    onClick = { viewModel.selectTab(CommandCreateTab.AI_SUGGESTION) },
                    text = { Text("AI 建议") },
                )
                Tab(
                    selected = uiState.tab == CommandCreateTab.MANUAL_TEMPLATE,
                    onClick = { viewModel.selectTab(CommandCreateTab.MANUAL_TEMPLATE) },
                    text = { Text("手动模板") },
                )
            }

            when {
                uiState.notEnabled -> EmptyState(text = "命令执行功能未在后端启用")
                uiState.isLoading -> LoadingState()
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 目标服务器（详情页进入已预选；从列表进入需选择）
                    ServerPicker(
                        servers = uiState.servers,
                        serverId = uiState.serverId,
                        enabled = uiState.servers.isNotEmpty(),
                        onSelected = viewModel::selectServer,
                    )

                    when (uiState.tab) {
                        CommandCreateTab.AI_SUGGESTION -> {
                            OutlinedTextField(
                                value = uiState.intent,
                                onValueChange = viewModel::updateIntent,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("描述运维意图，如：查看磁盘占用最高的目录") },
                                minLines = 2,
                                maxLines = 5,
                                enabled = !uiState.isSubmitting,
                            )
                            SubmitButton(
                                text = "生成建议命令",
                                enabled = uiState.intent.isNotBlank() && uiState.serverId > 0 && !uiState.isSubmitting,
                                isSubmitting = uiState.isSubmitting,
                                onClick = viewModel::submitSuggestion,
                            )
                            uiState.suggestedRuns.forEach { run ->
                                SuggestedRunCard(run = run, onClick = { onOpenDetail(run.id) })
                            }
                        }

                        CommandCreateTab.MANUAL_TEMPLATE -> {
                            TemplatePicker(
                                templates = uiState.templates,
                                selectedTemplateId = uiState.selectedTemplateId,
                                onSelected = viewModel::selectTemplate,
                            )
                            val template = uiState.templates.firstOrNull { it.id == uiState.selectedTemplateId }
                            if (template != null) {
                                TemplateParamsForm(
                                    template = template,
                                    params = uiState.params,
                                    paramError = uiState.paramError,
                                    enabled = !uiState.isSubmitting,
                                    onParamChanged = viewModel::updateParam,
                                )
                                SubmitButton(
                                    text = "创建待审批命令",
                                    enabled = uiState.serverId > 0 && !uiState.isSubmitting,
                                    isSubmitting = uiState.isSubmitting,
                                    onClick = { viewModel.submitManual(onCreated = onOpenDetail) },
                                )
                            }
                        }
                    }

                    uiState.errorMessage?.let { message ->
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
                }
            }
        }
    }
}

@Composable
private fun SubmitButton(text: String, enabled: Boolean, isSubmitting: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(text)
    }
}

/** 目标服务器选择器；已预选时仅展示。 */
@Composable
private fun ServerPicker(
    servers: List<com.susumonitor.data.model.Server>,
    serverId: Long,
    enabled: Boolean,
    onSelected: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        serverId > 0 -> "服务器 #$serverId"
        else -> "选择目标服务器"
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "目标：$label", style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text("${server.name} (#${server.id})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(server.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 白名单模板选择器。 */
@Composable
private fun TemplatePicker(
    templates: List<CommandTemplate>,
    selectedTemplateId: String?,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = templates.firstOrNull { it.id == selectedTemplateId }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = selected?.id ?: "选择命令模板",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            selected?.let {
                Text(
                    text = it.argv.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            templates.forEach { template ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(template.id)
                            Text(
                                text = template.argv.joinToString(" "),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        onSelected(template.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 按模板参数定义动态生成输入框。 */
@Composable
private fun TemplateParamsForm(
    template: CommandTemplate,
    params: Map<String, String>,
    paramError: String?,
    enabled: Boolean,
    onParamChanged: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (template.params.isEmpty()) {
            Text(
                text = "该模板无需参数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        template.params.forEach { spec ->
            OutlinedTextField(
                value = params[spec.name] ?: "",
                onValueChange = { onParamChanged(spec.name, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(spec.name) },
                supportingText = { Text("格式：${spec.pattern}") },
                isError = paramError?.startsWith("参数「${spec.name}」") == true,
                singleLine = true,
                enabled = enabled,
            )
        }
        paramError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** AI 建议生成的命令卡片（自动审批策略开启时可能直接为执行中/终态）。 */
@Composable
private fun SuggestedRunCard(run: com.susumonitor.data.model.CommandRun, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    CommandStatusBadge(run.status)
                    if (run.approvalMode == CommandApprovalModeValues.AUTO) {
                        CommandAutoApprovalBadge()
                    }
                }
                Text(
                    text = run.templateId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = run.renderedCommand,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            run.proposal?.reason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (run.approvalMode == CommandApprovalModeValues.AUTO) {
                    "已按自动审批策略下发，点击查看结果 →"
                } else {
                    "点击查看并审批 →"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

package com.susumonitor.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.AlertLevelValues
import com.susumonitor.data.model.AlertMetricValues
import com.susumonitor.data.model.AlertRule
import com.susumonitor.data.model.CreateAlertRuleRequest
import com.susumonitor.data.model.UpdateAlertRuleRequest
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.util.TimeFormatter

/** 告警运算符常量（与 OpenAPI 对齐）。 */
private object AlertOperatorValues {
    const val GT = ">"
    const val GTE = ">="
    const val LT = "<"
    const val LTE = "<="
}

/**
 * 告警规则页：列表 + 新建/编辑对话框 + 启停（admin）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertRulesScreen(
    onBack: () -> Unit,
    viewModel: AlertRulesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editingRule by remember { mutableStateOf<AlertRule?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deletingRule by remember { mutableStateOf<AlertRule?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("告警规则") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
                actions = {
                    Button(onClick = { showCreateDialog = true }) { Text("新建") }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.rules.isEmpty() -> LoadingState(Modifier.padding(innerPadding))
            uiState.errorMessage != null && uiState.rules.isEmpty() ->
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.loadAll() },
                    modifier = Modifier.padding(innerPadding),
                )
            uiState.rules.isEmpty() -> EmptyState("暂无告警规则", Modifier.padding(innerPadding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.rules, key = { it.id }) { rule ->
                    AlertRuleCard(
                        rule = rule,
                        serverName = viewModel.serverName(rule),
                        onToggle = { viewModel.toggleEnabled(rule) },
                        onEdit = { editingRule = rule },
                        onDelete = { deletingRule = rule },
                    )
                }
            }
        }
    }

    // 新建对话框
    if (showCreateDialog) {
        AlertRuleFormDialog(
            title = "新建告警规则",
            servers = uiState.servers,
            onDismiss = { showCreateDialog = false },
            onSubmit = { request ->
                viewModel.createRule(request) { showCreateDialog = false }
            },
        )
    }

    // 编辑对话框
    editingRule?.let { rule ->
        AlertRuleFormDialog(
            title = "编辑告警规则",
            servers = uiState.servers,
            initial = rule,
            onDismiss = { editingRule = null },
            onSubmit = { request ->
                // 编辑模式：仅提交可修改字段（阈值/等级/确认次数/通知/启用）
                viewModel.updateRule(
                    rule.id,
                    UpdateAlertRuleRequest(
                        thresholdValue = request.thresholdValue,
                        level = request.level,
                        enabled = rule.enabled,
                        confirmCount = request.confirmCount,
                        notifyEmail = request.notifyEmail,
                        notifyDingtalk = request.notifyDingtalk,
                        notifyWebhook = request.notifyWebhook,
                    ),
                ) { editingRule = null }
            },
        )
    }

    // 删除确认
    deletingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deletingRule = null },
            title = { Text("删除规则") },
            text = { Text("确定删除规则 #${rule.id}？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule.id)
                    deletingRule = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingRule = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AlertRuleCard(
    rule: AlertRule,
    serverName: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = metricLabel(rule.metric),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = levelLabel(rule.level),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (rule.level == "critical") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                text = "${serverName} · ${rule.operator} ${rule.thresholdValue} · 确认 ${if (rule.confirmCount > 1) "连续 ${rule.confirmCount} 次" else "1（立即）"}",
                style = MaterialTheme.typography.bodySmall,
            )
            // 通知渠道
            val channels = listOfNotNull(
                rule.notifyEmail?.takeIf { it.isNotBlank() }?.let { "邮件" },
                rule.notifyDingtalk?.takeIf { it.isNotBlank() }?.let { "钉钉" },
                rule.notifyWebhook?.takeIf { it.isNotBlank() }?.let { "Webhook" },
            )
            if (channels.isNotEmpty()) {
                Text(
                    text = "通知：${channels.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
                Text(
                    text = if (rule.enabled) "已启用" else "已停用",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
            Text(
                text = "更新于 ${TimeFormatter.formatLocal(rule.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertRuleFormDialog(
    title: String,
    servers: List<com.susumonitor.data.model.Server>,
    onDismiss: () -> Unit,
    onSubmit: (CreateAlertRuleRequest) -> Unit,
    initial: AlertRule? = null,
) {
    val isEdit = initial != null
    var serverId by remember { mutableStateOf(initial?.serverId) }
    var metric by remember { mutableStateOf(initial?.metric ?: AlertMetricValues.CPU) }
    var operator by remember { mutableStateOf(initial?.operator ?: AlertOperatorValues.GT) }
    var threshold by remember { mutableStateOf(initial?.thresholdValue?.toString() ?: "80") }
    var level by remember { mutableStateOf(initial?.level ?: AlertLevelValues.WARNING) }
    var confirmCount by remember { mutableStateOf(initial?.confirmCount?.toString() ?: "1") }
    var notifyEmail by remember { mutableStateOf(initial?.notifyEmail ?: "") }
    var notifyDingtalk by remember { mutableStateOf(initial?.notifyDingtalk ?: "") }
    var notifyWebhook by remember { mutableStateOf(initial?.notifyWebhook ?: "") }
    var serverMenuOpen by remember { mutableStateOf(false) }
    var metricMenuOpen by remember { mutableStateOf(false) }
    var operatorMenuOpen by remember { mutableStateOf(false) }
    var levelMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isEdit) {
                    Text(
                        text = "编辑模式不可修改适用服务器/指标/运算符",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 适用服务器（编辑锁定）
                ExposedDropdownMenuBox(expanded = serverMenuOpen, onExpandedChange = {
                    if (!isEdit) serverMenuOpen = it
                }) {
                    OutlinedTextField(
                        value = if (serverId == null) "全局规则" else
                            servers.firstOrNull { it.id == serverId }?.name ?: "#$serverId",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("适用服务器") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverMenuOpen) },
                        enabled = !isEdit,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = serverMenuOpen,
                        onDismissRequest = { serverMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("全局规则") },
                            onClick = { serverId = null; serverMenuOpen = false },
                        )
                        servers.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = { serverId = s.id; serverMenuOpen = false },
                            )
                        }
                    }
                }
                // 指标（编辑锁定）
                ExposedDropdownMenuBox(expanded = metricMenuOpen, onExpandedChange = {
                    if (!isEdit) metricMenuOpen = it
                }) {
                    OutlinedTextField(
                        value = metricLabel(metric),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("指标") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = metricMenuOpen) },
                        enabled = !isEdit,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = metricMenuOpen, onDismissRequest = { metricMenuOpen = false }) {
                        listOf(
                            AlertMetricValues.CPU,
                            AlertMetricValues.MEMORY,
                            AlertMetricValues.DISK,
                            AlertMetricValues.TEMPERATURE,
                            AlertMetricValues.LOAD,
                        ).forEach { m ->
                            DropdownMenuItem(
                                text = { Text(metricLabel(m)) },
                                onClick = { metric = m; metricMenuOpen = false },
                            )
                        }
                    }
                }
                // 运算符（编辑锁定）
                ExposedDropdownMenuBox(expanded = operatorMenuOpen, onExpandedChange = {
                    if (!isEdit) operatorMenuOpen = it
                }) {
                    OutlinedTextField(
                        value = operator,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("运算符") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = operatorMenuOpen) },
                        enabled = !isEdit,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = operatorMenuOpen, onDismissRequest = { operatorMenuOpen = false }) {
                        listOf(AlertOperatorValues.GT, AlertOperatorValues.GTE, AlertOperatorValues.LT, AlertOperatorValues.LTE)
                            .forEach { op ->
                                DropdownMenuItem(
                                    text = { Text(op) },
                                    onClick = { operator = op; operatorMenuOpen = false },
                                )
                            }
                    }
                }
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("阈值") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 等级
                ExposedDropdownMenuBox(expanded = levelMenuOpen, onExpandedChange = { levelMenuOpen = it }) {
                    OutlinedTextField(
                        value = levelLabel(level),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("等级") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = levelMenuOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = levelMenuOpen, onDismissRequest = { levelMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("警告") },
                            onClick = { level = AlertLevelValues.WARNING; levelMenuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text("严重") },
                            onClick = { level = AlertLevelValues.CRITICAL; levelMenuOpen = false },
                        )
                    }
                }
                OutlinedTextField(
                    value = confirmCount,
                    onValueChange = { confirmCount = it.filter { c -> c.isDigit() } },
                    label = { Text("确认次数（1-100）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "通知设置（可选）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = notifyEmail,
                    onValueChange = { notifyEmail = it },
                    label = { Text("邮件（逗号分隔）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notifyDingtalk,
                    onValueChange = { notifyDingtalk = it },
                    label = { Text("钉钉 Webhook URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notifyWebhook,
                    onValueChange = { notifyWebhook = it },
                    label = { Text("自定义 Webhook URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val thresholdValue = threshold.toDoubleOrNull()
                    val confirmValue = confirmCount.toIntOrNull()?.coerceIn(1, 100)
                    if (thresholdValue == null || confirmValue == null) return@TextButton
                    val req = CreateAlertRuleRequest(
                        serverId = serverId,
                        metric = metric,
                        operator = operator,
                        thresholdValue = thresholdValue,
                        level = level,
                        confirmCount = confirmValue,
                        notifyEmail = notifyEmail.trim().ifEmpty { null },
                        notifyDingtalk = notifyDingtalk.trim().ifEmpty { null },
                        notifyWebhook = notifyWebhook.trim().ifEmpty { null },
                    )
                    if (isEdit) {
                        onSubmit(
                            req.copy(
                                confirmCount = initial?.confirmCount ?: confirmValue,
                            ),
                        )
                    } else {
                        onSubmit(req)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun metricLabel(metric: String): String = when (metric) {
    "cpu" -> "CPU"
    "memory" -> "内存"
    "disk" -> "磁盘"
    "temperature" -> "温度"
    "load" -> "负载"
    else -> metric
}

private fun levelLabel(level: String): String = when (level) {
    "warning" -> "警告"
    "critical" -> "严重"
    else -> level
}

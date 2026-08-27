package com.susumonitor.ui.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.SshAuthTypeValues
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.util.TimeFormatter
import com.susumonitor.util.ValueFormatter

/**
 * 服务器详情页：信息卡 + 投递遥测 + 实时指标 + 管理操作（admin）+ 终端入口（approved）。
 * @param isAdmin 当前用户是否 admin（控制管理按钮）
 * @param isApproved 当前用户是否已审核通过（控制终端入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    serverId: Long,
    isAdmin: Boolean,
    isApproved: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMetrics: () -> Unit,
    onTerminal: () -> Unit,
    viewModel: ServerDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showHostKeyDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.server?.name ?: "服务器详情") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        // 下拉刷新：详情内容可滚动，静默重拉
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
        when {
            uiState.isLoading && uiState.server == null -> LoadingState()
            uiState.errorMessage != null && uiState.server == null ->
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.load() },
                )
            uiState.server != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 动作栏：完全自绘，绕过 OutlinedButton，直接 Box+Text 保证文字可见
                val isDark = isSystemInDarkTheme()
                val actBg = if (isDark) Color(0xFF2D2540) else Color(0xFFEDE7F6)
                val actFg = if (isDark) Color.White else Color(0xFF7A5CB8)
                val actBorder = if (isDark) Color(0xFFD8C7F0) else Color(0xFF9C7BD8)

                @Composable
                fun ActionBtn(label: String, fg: Color = actFg, onClick: () -> Unit) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, actBorder, RoundedCornerShape(50))
                            .background(actBg, RoundedCornerShape(50))
                            .clickable { onClick() }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = fg,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionBtn("实时监控") { onMetrics() }
                    if (isApproved) ActionBtn("终端") { onTerminal() }
                    if (isAdmin) {
                        ActionBtn("编辑") { onEdit() }
                        ActionBtn("主机指纹") { showHostKeyDialog = true }
                        ActionBtn("Agent令牌") { showTokenDialog = true }
                        ActionBtn("删除", fg = MaterialTheme.colorScheme.error) { showDeleteDialog = true }
                    }
                }

                ServerInfoCard(uiState)
                DeliveryTelemetryCard(uiState.status)

                // 管理操作结果展示
                uiState.sshTest?.let { result ->
                    InfoCard("SSH 测试") {
                        InfoRow("结果", if (result.connected) "连接成功" else "连接失败")
                        InfoRow("认证", result.authType)
                        InfoRow("耗时", "${result.durationMs} ms")
                        InfoRow("测试时间", TimeFormatter.formatLocal(result.testedAt))
                    }
                }
                uiState.hostKeyResult?.let { result ->
                    InfoCard("主机指纹") {
                        InfoRow("算法", result.hostKeyAlgorithm)
                        InfoRow("指纹", result.hostKeyFingerprint)
                        InfoRow("操作", result.operation)
                    }
                }
                uiState.agentToken?.let { token ->
                    InfoCard("Agent Token（仅显示一次）") {
                        Text(
                            text = token.agentToken,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = viewModel::clearResults) { Text("关闭") }
                    }
                }
                if (uiState.actionInProgress) {
                    LoadingState(modifier = Modifier.fillMaxWidth())
                }
                if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                MetricsGrid(uiState.latest)
            }
        }
        }
    }

    // 主机指纹确认对话框
    if (showHostKeyDialog) {
        HostKeyDialog(
            onConfirm = { fingerprint, replace ->
                viewModel.confirmHostKey(fingerprint, replace)
                showHostKeyDialog = false
            },
            onDismiss = { showHostKeyDialog = false },
        )
    }

    // Agent Token 管理对话框
    if (showTokenDialog) {
        AgentTokenDialog(
            onRegister = { viewModel.registerAgentToken(); showTokenDialog = false },
            onRotate = { viewModel.rotateAgentToken(); showTokenDialog = false },
            onRevoke = { viewModel.revokeAgentToken(); showTokenDialog = false },
            onDismiss = { showTokenDialog = false },
        )
    }

    // 删除确认：详情页不直接删除，提示后返回列表页操作（列表页有删除入口与确认）
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除服务器") },
            text = { Text("请在服务器列表页执行删除操作（此页仅展示信息）。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onBack()
                }) { Text("返回列表") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun HostKeyDialog(
    onConfirm: (fingerprint: String, replace: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var fingerprint by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    val valid = Regex("^SHA256:[A-Za-z0-9+/]{43}$").matches(fingerprint.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认主机指纹") },
        text = {
            Column {
                Text(
                    text = "输入目标 SSH 主机公钥指纹（格式 SHA256:...），用于首次确认或轮换。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text("SHA256: 指纹") },
                    singleLine = true,
                    isError = fingerprint.isNotEmpty() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "如需覆盖已登记指纹，勾选替换（仅在你已验证新指纹时使用）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fingerprint.trim(), replace) },
                enabled = valid,
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AgentTokenDialog(
    onRegister: () -> Unit,
    onRotate: () -> Unit,
    onRevoke: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent Token 管理") },
        text = {
            Text(
                text = "生成：为服务器创建 Agent 接入令牌（首次）。\n轮换：重新生成令牌（旧令牌立即失效）。\n吊销：撤销现有令牌（Agent 将无法连接）。",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRegister) { Text("生成") }
                TextButton(onClick = onRotate) { Text("轮换") }
                TextButton(onClick = onRevoke) { Text("吊销") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ServerInfoCard(uiState: ServerDetailUiState) {
    val server = uiState.server ?: return
    val status = uiState.status
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow("主机", server.host)
            server.description?.takeIf { it.isNotBlank() }?.let { InfoRow("描述", it) }
            InfoRow("SSH", "${server.sshUser}@${server.sshHost}:${server.sshPort}")
            InfoRow("认证方式", if (server.sshAuthType == SshAuthTypeValues.PRIVATE_KEY) "私钥" else "密码")
            InfoRow("状态", statusText(server.status))
            InfoRow("Agent", agentText(server.agentStatus))
            InfoRow("Agent ID", server.agentId ?: "-")
            InfoRow("最近心跳", TimeFormatter.formatLocal(status?.lastHeartbeatAt))
            InfoRow("创建时间", TimeFormatter.formatLocal(server.createdAt))
        }
    }
}

/** 投递遥测卡（Agent 可靠投递积压/死信）。 */
@Composable
private fun DeliveryTelemetryCard(status: com.susumonitor.data.model.ServerStatus?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "投递遥测", style = MaterialTheme.typography.titleMedium)
            if (status == null) {
                Text("暂无数据", style = MaterialTheme.typography.bodySmall)
            } else {
                InfoRow("积压条数", status.deliveryPendingCount?.toString() ?: "-")
                InfoRow("积压字节", status.deliveryPendingBytes?.let { ValueFormatter.bytes(it.toDouble()) } ?: "-")
                InfoRow("最旧积压", status.deliveryOldestCollectedAt?.let { TimeFormatter.formatLocal(it) } ?: "-")
                InfoRow("丢弃数", status.deliveryDropCount?.toString() ?: "-")
                InfoRow("死信条数", status.deliveryDeadLetterCount?.toString() ?: "-")
                InfoRow("死信字节", status.deliveryDeadLetterBytes?.let { ValueFormatter.bytes(it.toDouble()) } ?: "-")
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MetricsGrid(latest: Metrics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "实时指标", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            if (latest == null) {
                Text(
                    text = "暂无指标数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "采集时间 ${TimeFormatter.formatLocal(latest.collectedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricRow(
                    cpu = latest.cpuPercent,
                    memory = latest.memoryPercent,
                    disk = latest.diskPercent,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricItem("网络 RX", ValueFormatter.bytes(latest.netRx), Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricItem("网络 TX", ValueFormatter.bytes(latest.netTx), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricItem("温度", ValueFormatter.metricValue("temperature", latest.temperature), Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricItem("负载", ValueFormatter.metricValue("load", latest.loadAvg), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricRow(cpu: Double?, memory: Double?, disk: Double?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        MetricItem("CPU", ValueFormatter.percent(cpu), Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        MetricItem("内存", ValueFormatter.percent(memory), Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        MetricItem("磁盘", ValueFormatter.percent(disk), Modifier.weight(1f))
    }
}

@Composable
private fun MetricItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleSmall)
    }
}

private fun statusText(status: String): String = when (status) {
    "online" -> "在线"
    "offline" -> "离线"
    else -> status
}

private fun agentText(agentStatus: String): String = when (agentStatus) {
    "online" -> "在线"
    "offline" -> "离线"
    else -> agentStatus
}

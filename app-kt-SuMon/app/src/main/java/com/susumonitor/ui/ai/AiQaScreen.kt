package com.susumonitor.ui.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.AiToolCall
import com.susumonitor.ui.theme.StatusWarning

/**
 * AI 运维问答页（F2，AI Tab 主体）：会话式消息列表 + 目标服务器选择 + 工具调用审计。
 * 后端为单轮无状态接口，会话历史仅本地内存保留。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiQaScreen(
    onOpenCommands: () -> Unit = {},
    viewModel: AiQaViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 新消息到达时滚到底部
    LaunchedEffect(uiState.messages.size, uiState.isSending) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 运维助手") },
                actions = {
                    TextButton(onClick = onOpenCommands) { Text("命令审批") }
                    IconButton(onClick = { viewModel.clearConversation() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空会话")
                    }
                },
            )
        },
        bottomBar = {
            AiQaInputBar(
                input = uiState.input,
                isSending = uiState.isSending,
                servers = uiState.servers,
                selectedServerId = uiState.selectedServerId,
                onInputChanged = viewModel::updateInput,
                onServerSelected = viewModel::selectServer,
                onSend = viewModel::send,
            )
        },
    ) { innerPadding ->
        if (uiState.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("向 AI 提问运维问题", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "AI 仅能调用只读工具查询状态、指标与告警，\n不会执行任何变更操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    AiChatBubble(message = message)
                }
                if (uiState.isSending) {
                    item(key = "sending") {
                        Row(
                            modifier = Modifier.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
                            Text(
                                text = "AI 正在思考…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 底部输入栏：目标服务器选择 + 问题输入 + 发送。 */
@Composable
private fun AiQaInputBar(
    input: String,
    isSending: Boolean,
    servers: List<com.susumonitor.data.model.Server>,
    selectedServerId: Long?,
    onInputChanged: (String) -> Unit,
    onServerSelected: (Long?) -> Unit,
    onSend: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            ServerSelector(
                servers = servers,
                selectedServerId = selectedServerId,
                onSelected = onServerSelected,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入运维问题…") },
                    maxLines = 4,
                    enabled = !isSending,
                )
                IconButton(onClick = onSend, enabled = !isSending && input.isNotBlank()) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "发送",
                        tint = if (!isSending && input.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 目标服务器下拉：全局 + 全部在线服务器。 */
@Composable
private fun ServerSelector(
    servers: List<com.susumonitor.data.model.Server>,
    selectedServerId: Long?,
    onSelected: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when (val id = selectedServerId) {
        null -> "全局提问"
        else -> "服务器 #${servers.firstOrNull { it.id == id }?.name ?: id.toString()}"
    }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "范围：$selectedLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择服务器")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全局提问") },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
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

/** 单条消息气泡：用户右对齐主色底，AI/错误左对齐卡片。 */
@Composable
private fun AiChatBubble(message: AiChatMessage) {
    val isUser = message.role == AiChatRole.USER
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = when {
                        isUser -> MaterialTheme.colorScheme.primaryContainer
                        message.role == AiChatRole.ERROR -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 12.dp,
                    ),
                )
                .padding(12.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                    message.role == AiChatRole.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (!isUser && message.role == AiChatRole.AI) {
            AiMessageMeta(message = message)
        }
    }
}

/** AI 消息附加信息：降级提示 + 可折叠工具调用审计 + Token 用量。 */
@Composable
private fun AiMessageMeta(message: AiChatMessage) {
    var showToolCalls by remember(message.id) { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 4.dp, start = 4.dp)) {
        if (message.degraded == true || message.modelUsed == false) {
            Text(
                text = "⚠ 降级为服务端确定性摘要，本次非模型生成",
                style = MaterialTheme.typography.labelSmall,
                color = StatusWarning,
            )
        }
        if (message.toolCalls.isNotEmpty()) {
            Text(
                text = if (showToolCalls) "▾ 工具调用 (${message.toolCalls.size})" else "▸ 工具调用 (${message.toolCalls.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { showToolCalls = !showToolCalls }
                    .padding(vertical = 2.dp),
            )
            if (showToolCalls) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        message.toolCalls.forEach { call ->
                            ToolCallRow(call)
                        }
                    }
                }
            }
        }
        message.usage?.let { usage ->
            Text(
                text = "tokens ${usage.totalTokens} · ${usage.currency} ${usage.estimatedCost}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolCallRow(call: AiToolCall) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = call.tool,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = call.args,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

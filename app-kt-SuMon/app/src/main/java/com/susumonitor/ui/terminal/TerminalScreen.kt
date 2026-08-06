package com.susumonitor.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.api.TerminalPhase

/**
 * SSH 终端页（自绘简化版）：SimpleTerminalView 渲染 + WS 双向传输。
 *
 * 输出：TerminalClient.onOutput → 行缓冲 feedTerminal。
 * 输入：软键盘字符/回车 → TerminalClient.sendInput。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    serverId: Long,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lines = remember { mutableStateOf(emptyList<String>()) }

    // 输出回调：WS terminal.output → 行缓冲
    // 注意：必须用不可变副本替换列表以触发 Compose 重组（直接改 MutableList 内容不通知快照）
    LaunchedEffect(Unit) {
        viewModel.setOutputCallback { bytes ->
            val next = lines.value.toMutableList()
            next.feedTerminal(bytes)
            lines.value = next
        }
    }

    // 首次进入：建立连接并 open
    LaunchedEffect(Unit) {
        viewModel.open(cols = 80, rows = 24)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH 终端 · #$serverId") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SimpleTerminalView(
                lines = lines.value,
                onInput = viewModel::sendInput,
                modifier = Modifier.fillMaxSize(),
            )

            // 错误/未连接提示覆盖层
            if (uiState.errorMessage != null || uiState.phase == TerminalPhase.IDLE) {
                ColumnOverlay(
                    message = uiState.errorMessage ?: "正在连接…",
                    showRetry = uiState.errorMessage != null,
                    onRetry = { viewModel.retry(cols = 80, rows = 24) },
                )
            }
        }
    }

    // 页面销毁时关闭终端
    DisposableEffect(Unit) {
        onDispose {
            viewModel.close()
        }
    }
}

@Composable
private fun ColumnOverlay(message: String, showRetry: Boolean, onRetry: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (showRetry) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onRetry) { Text("重连") }
        }
    }
}

package com.susumonitor.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.api.TerminalPhase

/** 功能键行各键：显示名 → 发送字节。 */
private val SpecialKeys = listOf(
    "Ctrl+C" to "\u0003",
    "Ctrl+Z" to "\u001a",
    "Tab" to "\t",
    "Esc" to "\u001b",
    "↑" to "\u001b[A",
    "↓" to "\u001b[B",
    "←" to "\u001b[D",
    "→" to "\u001b[C",
)

/**
 * SSH 终端页（自研 ANSI 终端模拟器）：TerminalEmulator 解析渲染 + WS 双向传输。
 *
 * 输出：TerminalClient.onOutput → TerminalBuffer.feed（增量 ANSI 解析，版本号驱动重组）。
 * 输入：软键盘字符/回车 → TerminalClient.sendInput；功能键行/物理键盘组合 → 控制字节。
 * 尺寸：按容器实际宽高换算 cols/rows，open 时用实际尺寸、变化时发 resize 帧并重建缓冲网格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    serverId: Long,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buffer = remember { TerminalBuffer() }
    // 每次 feed 递增版本号，驱动 Compose 重组并重绘
    var bufferVersion by remember { mutableLongStateOf(0L) }

    // 输出回调：WS terminal.output → 终端模拟器（UTF-8/ANSI 增量解析）
    // 注意：必须读 bufferVersion 触发重组（缓冲为普通对象，不通知 Compose 快照）
    LaunchedEffect(Unit) {
        viewModel.setOutputCallback { bytes ->
            buffer.feed(bytes)
            bufferVersion++
        }
        // CSI n 状态报告应答回传 PTY 输入通道
        buffer.reportOutput = { report -> viewModel.sendInput(report.toByteArray(Charsets.UTF_8)) }
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 功能键行：发送控制字节（软键盘没有 Ctrl/方向键）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SpecialKeys.forEach { (label, seq) ->
                    Surface(
                        onClick = { viewModel.sendInput(seq.toByteArray(Charsets.UTF_8)) },
                        color = Color(0xFF3C3C3C),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = label,
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // 终端区：按实际尺寸换算 cols/rows
            var opened by remember { mutableStateOf(false) }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val charWidth = with(LocalDensity.current) { 14.sp.toDp() * 0.6f }
                val lineHeight = 20.dp
                val cols = ((maxWidth - 16.dp) / charWidth).toInt().coerceIn(2, 300)
                val rows = (maxHeight / lineHeight).toInt().coerceIn(1, 100)

                // 缓冲网格跟随尺寸；首次 open 前清空旧缓冲，之后尺寸变化重建网格并发 resize
                LaunchedEffect(cols, rows) {
                    buffer.resize(cols, rows)
                    if (!opened) {
                        buffer.clear()
                        viewModel.open(cols = cols, rows = rows)
                        opened = true
                    } else if (uiState.phase == TerminalPhase.OPEN) {
                        viewModel.resize(cols, rows)
                    }
                    bufferVersion++
                }

                // 版本号状态读取：每次 feed 递增 → 触发重组（快照由 visibleRows() 每次重建）
                @Suppress("UNUSED_VARIABLE")
                val snapshotVersion = bufferVersion
                SimpleTerminalView(
                    rows = buffer.visibleRows(),
                    cols = buffer.screenCols,
                    screenRows = buffer.screenRows,
                    cursorRow = buffer.cursorScreenRow,
                    cursorCol = buffer.cursorScreenCol,
                    cursorVisible = buffer.isCursorVisible,
                    onInput = viewModel::sendInput,
                    modifier = Modifier.fillMaxSize(),
                )

                // 错误/未连接提示覆盖层
                if (uiState.errorMessage != null || uiState.phase == TerminalPhase.IDLE) {
                    ColumnOverlay(
                        message = uiState.errorMessage ?: "正在连接…",
                        showRetry = uiState.errorMessage != null,
                        onRetry = { viewModel.retry(cols = cols, rows = rows) },
                    )
                }
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
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (showRetry) {
            Box(modifier = Modifier.padding(8.dp))
            Button(onClick = onRetry) { Text("重连") }
        }
    }
}

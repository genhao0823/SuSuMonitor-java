package com.susumonitor.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.api.TerminalPhase
import kotlin.math.abs
import kotlin.math.roundToInt

/** 功能键行各键：显示名 → 发送字节。补充 shell 高频符号与常用控制键。 */
internal val SpecialKeys = listOf(
    "Ctrl+C" to "\u0003",
    "Ctrl+Z" to "\u001a",
    "Ctrl+L" to "\u000c",
    "Tab" to "\t",
    "Esc" to "\u001b",
    "↑" to "\u001b[A",
    "↓" to "\u001b[B",
    "←" to "\u001b[D",
    "→" to "\u001b[C",
    "/" to "/",
    "~" to "~",
    "|" to "|",
    "-" to "-",
    "_" to "_",
    "$" to "$",
    "." to ".",
)

/**
 * 将手指竖向位移转换为终端历史回退偏移：手指向下时查看更早内容，向上时回到底部。
 */
internal fun nextTerminalScrollOffset(
    currentOffset: Int,
    panY: Float,
    lineHeightPx: Float,
    maxOffset: Int,
): Int {
    if (lineHeightPx <= 0f) return currentOffset.coerceIn(0, maxOffset.coerceAtLeast(0))
    val lineDelta = (panY / lineHeightPx).roundToInt()
    return (currentOffset + lineDelta).coerceIn(0, maxOffset.coerceAtLeast(0))
}

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
    // 缓冲与版本号由 ViewModel 持有：配置变更（旋转）后内容保留，不清屏不重开会话。
    val buffer = viewModel.buffer
    val bufferVersion by viewModel.bufferVersion.collectAsStateWithLifecycle()

    // 新会话建立（首开/断线重连/手动重试）时清空旧缓冲
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == TerminalPhase.AWAITING_OPEN) {
            viewModel.clearBuffer()
        }
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
        // imePadding：软键盘弹出时收缩终端区高度。edge-to-edge 下 windowSoftInputMode
        // adjustResize 失效，必须显式消费 IME insets，否则光标与最新输出被键盘遮挡。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // 功能键行：发送控制字节（软键盘没有 Ctrl/方向键），窄屏横向滚动避免溢出
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D))
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
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
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // 终端区：按实际尺寸换算 cols/rows
            // opened 用 rememberSaveable：旋转/配置变更后恢复 true，避免重复 clear+open 清屏
            var opened by rememberSaveable { mutableStateOf(false) }
            // 字号（sp）：捏合缩放调整，范围 10–24
            var fontSizeSp by remember { mutableFloatStateOf(11f) }
            // 滚动回退偏移（行）：0 = 视口贴底显示最新输出，>0 = 回看历史
            var scrollOffsetLines by remember { mutableStateOf(0) }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val fontSize = fontSizeSp.sp
                // 实测等宽字符宽高：与绘制层共用同一来源，消除估算/实测双标准的右缝或裁切
                val textMeasurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val cellSize = remember(textMeasurer, fontSize) {
                    textMeasurer.measure(
                        "M",
                        TextStyle(fontSize = fontSize, fontFamily = FontFamily.Monospace),
                    ).size
                }
                val charWidth = with(density) { cellSize.width.toDp() }
                val lineHeight = with(density) { cellSize.height.toDp() }
                val lineHeightPx = cellSize.height.toFloat()
                val cols = (maxWidth / charWidth).toInt().coerceIn(2, 300)
                val rows = (maxHeight / lineHeight).toInt().coerceIn(1, 100)

                // 缓冲网格跟随尺寸；首次 open 前清空旧缓冲，之后尺寸变化重建网格并发 resize。
                // resize 帧去抖 150ms：键盘弹出/收起动画期间容器高度连续变化，避免反复触发
                // 服务端 stty size 与 shell 重绘；本地 buffer.resize 立即执行保证渲染正确。
                LaunchedEffect(cols, rows) {
                    viewModel.resizeBuffer(cols, rows)
                    if (!opened) {
                        buffer.clear()
                        viewModel.open(cols = cols, rows = rows)
                        opened = true
                    } else if (uiState.phase == TerminalPhase.OPEN) {
                        // 尺寸变化：去抖后发 resize，保留会话与缓冲（旋转不清屏）
                        kotlinx.coroutines.delay(150)
                        viewModel.resize(cols, rows)
                    } else if (uiState.phase == TerminalPhase.IDLE) {
                        // 进程重建恢复：rememberSaveable 保留了 opened，但会话已随进程消亡，重新打开
                        viewModel.open(cols = cols, rows = rows)
                    }
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
                    fontSize = fontSize,
                    charWidthPx = cellSize.width.toFloat(),
                    lineHeightPx = lineHeightPx,
                    textMeasurer = textMeasurer,
                    scrollOffsetLines = scrollOffsetLines,
                    onInput = viewModel::sendInput,
                    modifier = Modifier
                        .fillMaxSize()
                        // 手势统一由 detectTransformGestures 处理（避免多检测器抢事件）：
                        // 单指竖向拖动 → 滚动回退历史（pan.y）；双指捏合 → 调整字号（zoom）。
                        .pointerInput(lineHeightPx) {
                            var baseSp = fontSizeSp
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom != 1f) {
                                    // zoom 为相对手势起点的累计缩放
                                    val next = (baseSp * zoom).coerceIn(10f, 24f)
                                    if (next != fontSizeSp) {
                                        baseSp = next
                                        fontSizeSp = next
                                    }
                                } else if (abs(pan.y) > abs(pan.x)) {
                                    // 手指向下滑（pan.y > 0）= 页面向上查看更早历史，偏移增大。
                                    // 手指向上滑（pan.y < 0）= 页面向下回到最新输出，偏移减小。
                                    // buffer 为稳定引用，实时读取 scrollback 行数，避免闭包捕获过期值
                                    val maxOffset =
                                        if (buffer.isUsingAlternateScreen) 0 else buffer.scrollbackSize()
                                    val next = nextTerminalScrollOffset(
                                        currentOffset = scrollOffsetLines,
                                        panY = pan.y,
                                        lineHeightPx = lineHeightPx,
                                        maxOffset = maxOffset,
                                    )
                                    if (next != scrollOffsetLines) scrollOffsetLines = next
                                }
                            }
                        },
                )

                // 回看历史时显示"回到底部"浮动按钮，点击贴回最新输出
                if (scrollOffsetLines > 0) {
                    Surface(
                        onClick = { scrollOffsetLines = 0 },
                        color = Color(0xFF3C3C3C),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "↓ 回到底部",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                // 错误/未连接/重连中提示覆盖层
                val overlayMessage = when {
                    uiState.phase == TerminalPhase.RECONNECTING ->
                        "连接断开，正在重连…（第 ${uiState.reconnectAttempts} 次）"
                    uiState.phase == TerminalPhase.IDLE -> "正在连接…"
                    else -> null
                }
                val showOverlay = uiState.errorMessage != null || overlayMessage != null
                if (showOverlay) {
                    ColumnOverlay(
                        message = uiState.errorMessage ?: overlayMessage.orEmpty(),
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

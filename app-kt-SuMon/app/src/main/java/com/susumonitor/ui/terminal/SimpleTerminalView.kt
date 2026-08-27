package com.susumonitor.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 终端底色（与旧实现一致）。 */
internal val TerminalBackground = Color(0xFF1E1E1E)

/** 默认前景色（调色板 7 的浅灰）。 */
private val TerminalDefaultFg = Color(0xFFE5E5E5)

/** 光标块颜色（半透明白）。 */
private val TerminalCursorColor = Color(0x66FFFFFF)

/**
 * 自研 ANSI 终端渲染组件：按格绘制（背景色块 + 同样式文本段 + 光标块）+ 软键盘输入。
 *
 * @param rows 可视快照（滚动回退 + 当前屏，由 TerminalBuffer.visibleRows() 提供）
 * @param cols 当前终端列数
 * @param screenRows 当前终端行数（快照尾部即屏幕行）
 * @param cursorRow 光标所在屏行（相对屏幕顶部）
 * @param cursorCol 光标列
 * @param cursorVisible 光标是否可见
 * @param onInput 输入回调（UTF-8 字节 → TerminalClient.sendInput）
 */
@Composable
fun SimpleTerminalView(
    rows: List<List<Cell>>,
    cols: Int,
    screenRows: Int,
    cursorRow: Int,
    cursorCol: Int,
    cursorVisible: Boolean,
    onInput: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontSize = 14.sp
    val lineHeight = 20.dp
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputBuffer by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .background(TerminalBackground)
            .pointerInput(Unit) {
                detectTapGestures { keyboardController?.show() }
            }
            // 物理键盘：Ctrl+字母 → 控制字节；Tab/Esc/方向键 → 转义序列
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = event.key
                when {
                    event.isCtrlPressed && key.keyCode in Key.A.keyCode..Key.Z.keyCode -> {
                        // Ctrl+A..Z = 0x01..0x1A
                        onInput(byteArrayOf((key.keyCode - Key.A.keyCode + 1).toByte()))
                        true
                    }
                    key == Key.Tab -> {
                        onInput("\t".toByteArray(Charsets.UTF_8))
                        true
                    }
                    key == Key.Escape -> {
                        onInput(byteArrayOf(0x1b))
                        true
                    }
                    key == Key.DirectionUp -> {
                        onInput("\u001b[A".toByteArray(Charsets.UTF_8))
                        true
                    }
                    key == Key.DirectionDown -> {
                        onInput("\u001b[B".toByteArray(Charsets.UTF_8))
                        true
                    }
                    key == Key.DirectionRight -> {
                        onInput("\u001b[C".toByteArray(Charsets.UTF_8))
                        true
                    }
                    key == Key.DirectionLeft -> {
                        onInput("\u001b[D".toByteArray(Charsets.UTF_8))
                        true
                    }
                    else -> false
                }
            },
    ) {
        TerminalGridCanvas(
            rows = rows,
            cols = cols,
            screenRows = screenRows,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = Modifier.fillMaxSize(),
        )

        // 透明输入框：软键盘字符/退格/回车 → onInput（等宽隐藏光标）
        // 输入框仅作键盘事件源；回显由 PTY 输出经 Canvas 渲染
        BasicTextField(
            value = inputBuffer,
            onValueChange = { newValue ->
                val prev = inputBuffer
                when {
                    // 退格删除：发送 DEL(0x7F)，PTY 处理删除
                    newValue.length < prev.length -> onInput(byteArrayOf(0x7F.toByte()))
                    // 回车：PTY 换行是 \r(0x0D)，非 \n
                    newValue.endsWith('\n') -> {
                        onInput("\r".toByteArray(Charsets.UTF_8))
                        inputBuffer = ""
                    }
                    else -> {
                        val added = newValue.drop(prev.length)
                        if (added.isNotEmpty()) {
                            onInput(added.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                // 保留完整文本供退格检测；上限防膨胀
                inputBuffer = if (newValue.length > 4096) newValue.takeLast(4096) else newValue
            },
            textStyle = TextStyle(color = Color.Transparent, fontSize = fontSize),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable(),
        )
    }
}

/** 终端快照中的光标描述（供 Canvas 绘制定位）。 */
private data class CursorInfo(val row: Int, val col: Int, val visible: Boolean)

/** 逐格 Canvas 渲染：背景色块 + 同样式文本段 + 光标块。 */
@Composable
private fun TerminalGridCanvas(
    rows: List<List<Cell>>,
    cols: Int,
    screenRows: Int,
    cursorRow: Int,
    cursorCol: Int,
    cursorVisible: Boolean,
    fontSize: TextUnit,
    lineHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val cursor = CursorInfo(cursorRow, cursorCol, cursorVisible)
    Canvas(modifier = modifier) {
        val lineHeightPx = lineHeight.toPx()
        val visibleCount = (size.height / lineHeightPx).toInt().coerceAtLeast(1)
        // 视口取快照末尾 visibleCount 行（滚动回退 + 屏幕底部）
        val visibleRows = rows.takeLast(visibleCount)
        val charWidthPx = textMeasurer.measure("M", TextStyle(
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
        )).size.width.toFloat().coerceAtLeast(1f)

        drawRows(visibleRows, cols, charWidthPx, lineHeightPx, fontSize, textMeasurer)
        drawCursor(rows, screenRows, cursor, visibleCount, charWidthPx, lineHeightPx)
    }
}

/** 绘制背景色块（跳过默认背景）。 */
private fun DrawScope.drawRowBackgrounds(row: List<Cell>, y: Float, charWidthPx: Float, lineHeightPx: Float) {
    var i = 0
    while (i < row.size) {
        val cell = row[i]
        if (cell.bg == Cell.DEFAULT_BG) {
            i++
            continue
        }
        var j = i
        while (j + 1 < row.size && row[j + 1].bg == cell.bg) j++
        val bgColor = Color(TerminalPalette.color(cell.bg))
        drawRect(
            color = bgColor,
            topLeft = Offset(i * charWidthPx, y),
            size = Size((j - i + 1) * charWidthPx, lineHeightPx),
        )
        i = j + 1
    }
}

/** 绘制同样式文本段（跳过空白格；空格背景已在背景阶段覆盖）。 */
private fun DrawScope.drawRowText(
    row: List<Cell>,
    y: Float,
    charWidthPx: Float,
    fontSize: TextUnit,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    var i = 0
    while (i < row.size) {
        val cell = row[i]
        if (cell.ch == ' ') {
            i++
            continue
        }
        var j = i
        while (j + 1 < row.size &&
            row[j + 1].ch != ' ' &&
            row[j + 1].fg == cell.fg &&
            row[j + 1].bold == cell.bold
        ) {
            j++
        }
        val text = buildString {
            for (k in i..j) append(row[k].ch)
        }
        val layout = textMeasurer.measure(
            AnnotatedString(text),
            TextStyle(
                fontSize = fontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                color = Color(TerminalPalette.color(cell.fg)),
            ),
        )
        drawText(layout, topLeft = Offset(i * charWidthPx, y))
        i = j + 1
    }
}

private fun DrawScope.drawRows(
    visibleRows: List<List<Cell>>,
    cols: Int,
    charWidthPx: Float,
    lineHeightPx: Float,
    fontSize: TextUnit,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    visibleRows.forEachIndexed { index, row ->
        val y = index * lineHeightPx
        val clipped = if (row.size <= cols) row else row.take(cols)
        drawRowBackgrounds(clipped, y, charWidthPx, lineHeightPx)
        drawRowText(clipped, y, charWidthPx, fontSize, textMeasurer)
    }
}

/**
 * 绘制光标块：屏幕行 r 在快照中的索引 = rows.size - screenRows + r。
 * 视口显示快照末尾 visibleCount 行，光标行不在视口内时不绘制。
 */
private fun DrawScope.drawCursor(
    rows: List<List<Cell>>,
    screenRows: Int,
    cursor: CursorInfo,
    visibleCount: Int,
    charWidthPx: Float,
    lineHeightPx: Float,
) {
    if (!cursor.visible) return
    val snapshotIndex = rows.size - screenRows + cursor.row
    val viewportStart = rows.size - visibleCount
    if (snapshotIndex < viewportStart) return
    val x = cursor.col * charWidthPx
    val y = (snapshotIndex - viewportStart) * lineHeightPx
    drawRect(
        color = TerminalCursorColor,
        topLeft = Offset(x, y),
        size = Size(charWidthPx, lineHeightPx),
    )
}

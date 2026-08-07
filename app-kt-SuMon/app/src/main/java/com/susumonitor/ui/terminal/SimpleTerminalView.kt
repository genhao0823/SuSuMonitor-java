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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 自绘简化终端渲染组件：等宽字体行缓冲 + 软键盘输入。
 *
 * @param lines 终端行缓冲（由上层管理，TerminalClient 输出回调追加）
 * @param onInput 输入回调（UTF-8 字节 → TerminalClient.sendInput）
 */
@Composable
fun SimpleTerminalView(
    lines: List<String>,
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
            .background(Color(0xFF1E1E1E))
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
        TerminalLinesCanvas(
            lines = lines,
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

/** 行缓冲 Canvas 渲染（等宽字体）。 */
@Composable
private fun TerminalLinesCanvas(
    lines: List<String>,
    fontSize: TextUnit,
    lineHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val style = TextStyle(
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFE0E0E0),
        )
        val lineHeightPx = lineHeight.toPx()
        val visibleCount = (size.height / lineHeightPx).toInt().coerceAtLeast(1)
        lines.takeLast(visibleCount)
            .forEachIndexed { index, line ->
                val layout = textMeasurer.measure(line, style)
                drawText(layout, topLeft = Offset(8f, index * lineHeightPx))
            }
    }
}

/**
 * 字节流 → 行缓冲（UTF-8 解码 + 基础 ANSI 剥离）。
 * 由 TerminalClient 输出回调调用。
 *
 * 换行语义：`\r\n` 整体视为换行（保留当前行内容，PTY 默认 onlcr 输出以 \r\n 结尾）；
 * 单独 `\n` 结束当前行另起新行；单独 `\r` 回到行首（覆盖重写，用于进度条等）。
 */
fun MutableList<String>.feedTerminal(bytes: ByteArray) {
    val text = String(bytes, Charsets.UTF_8)
    // ANSI 剥离：颜色/清行/光标移动等控制序列
    val cleaned = text.replace(Regex("\u001b\\[[0-9;]*[A-Za-z]"), "")

    if (this.isEmpty()) this.add("")
    var i = 0
    while (i < cleaned.length) {
        val c = cleaned[i]
        when {
            // \r\n 组合换行：保留当前行内容
            c == '\r' && i + 1 < cleaned.length && cleaned[i + 1] == '\n' -> {
                this.add("")
                i += 2
            }
            // 单独 \r：回到行首覆盖（后续字符从行首重写）
            c == '\r' -> {
                this[this.size - 1] = ""
                i++
            }
            c == '\n' -> {
                this.add("")
                i++
            }
            else -> {
                this[this.size - 1] = this[this.size - 1] + c
                i++
            }
        }
    }
    // 若末行为空且文本以换行结尾，移除多余空行
    if (cleaned.endsWith("\n") && this.size > 1 && this.last().isEmpty()) {
        this.removeAt(this.size - 1)
    }
    // 限制行数
    if (this.size > 2000) {
        repeat(this.size - 2000) { this.removeAt(0) }
    }
}

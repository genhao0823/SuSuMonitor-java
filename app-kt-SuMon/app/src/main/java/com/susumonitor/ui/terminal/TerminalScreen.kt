package com.susumonitor.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.susumonitor.api.TerminalPhase

/**
 * SSH 终端页：Termux TerminalView 渲染 PTY 输出 + WS 双向传输。
 *
 * 架构：用"无 shell"的 TerminalSession 驱动 TerminalView 渲染；
 * 输出（terminal.output）经 session.emulator.append 喂入，输入经
 * session.write 转发到 WS（TerminalClient.sendInput）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    serverId: Long,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            AndroidView(
                factory = { ctx ->
                    createTerminalView(ctx, viewModel)
                },
                update = { view ->
                    // 状态变化时处理连接/错误展示
                    handleStateChange(view, uiState, viewModel)
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 错误/未连接提示覆盖层
            if (uiState.errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    ColumnOverlay(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = { viewModel.retry(cols = 80, rows = 24) },
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
private fun ColumnOverlay(message: String, onRetry: () -> Unit) {
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
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Button(onClick = onRetry) { Text("重连") }
    }
}

/**
 * 创建 Termux TerminalView + 无 shell TerminalSession。
 * 输入拦截：TerminalViewClient.onKeyDown/onCodePoint 返回 true 并转发 WS（远程 PTY 回显）。
 * 输出注入：TerminalClient.onOutput → emulator.append。
 */
@SuppressLint("ViewConstructor")
private fun createTerminalView(context: Context, viewModel: TerminalViewModel): TerminalView {
    val terminalView = TerminalView(context, null)

    // 无 shell 会话：仅用于驱动 TerminalView 渲染（不启动本地进程）
    val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {}
        override fun onTitleChanged(session: TerminalSession) {}
        override fun onSessionFinished(session: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(cursorState: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    val session = TerminalSession(
        null, null, null, null, null, sessionClient,
    )
    session.initializeEmulator(80, 24)
    terminalView.attachSession(session)

    // 输入拦截：字符/按键转发到 WS（不写本地 emulator，由远程 PTY 回显）
    terminalView.setTerminalViewClient(object : com.termux.view.TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: android.view.MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean {
            // 转发按键字节到 WS（含方向键/回车等控制序列）
            val bytes = keyToByteArray(keyCode, e)
            if (bytes != null) {
                viewModel.sendInput(bytes)
                return true
            }
            return false
        }
        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
        override fun onLongPress(e: android.view.MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codepoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // 软键盘字符输入：UTF-8 编码转发 WS
            val str = String(Character.toChars(codepoint))
            viewModel.sendInput(str.toByteArray(Charsets.UTF_8))
            return true
        }
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    })

    // 输出回调：WS terminal.output → emulator.append
    viewModel.setOutputCallback { bytes ->
        val emulator: TerminalEmulator = session.getEmulator()
        emulator.append(bytes, bytes.size)
        terminalView.onScreenUpdated()
    }

    viewModel.markReady()
    return terminalView
}

/** 物理按键 → 终端字节序列（回车/退格/方向键等）。 */
private fun keyToByteArray(keyCode: Int, e: android.view.KeyEvent): ByteArray? = when (keyCode) {
    android.view.KeyEvent.KEYCODE_ENTER -> byteArrayOf('\r'.code.toByte())
    android.view.KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7f.toByte())
    android.view.KeyEvent.KEYCODE_TAB -> byteArrayOf('\t'.code.toByte())
    android.view.KeyEvent.KEYCODE_DPAD_UP -> byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())
    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> byteArrayOf(0x1b, '['.code.toByte(), 'C'.code.toByte())
    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> byteArrayOf(0x1b, '['.code.toByte(), 'D'.code.toByte())
    else -> null // 其他按键交回 TerminalView 默认处理（走 codePoint 路径）
}

/** 状态变化处理（连接/错误展示由 Compose 覆盖层负责，此处预留扩展）。 */
private fun handleStateChange(
    view: TerminalView,
    uiState: TerminalUiState,
    viewModel: TerminalViewModel,
) {
    // 尺寸自适应：会话打开后按当前 view 尺寸 resize
    if (uiState.phase == TerminalPhase.OPEN && uiState.ready) {
        // 具体 resize 由 TerminalView 尺寸变化触发（在 update 中测量）
    }
}

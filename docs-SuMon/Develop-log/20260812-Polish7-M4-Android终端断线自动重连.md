# 开发日志: Android 终端断线自动重连（指数退避 + UI 提示）

**日期**: 2026-08-12
**操作人**: ZCode
**阶段**: Polish-7 M4

## 背景

Android SSH 终端断线后不自动重连（README 已知遗留）：WS 半开断开后 TerminalClient 停留在 OPEN 相位，会话实际已丢失，用户只能手动点"重连"。本模块实现断线自动重连：WS 断开或异常关闭（非用户主动、非 shell 正常退出）时指数退避重新 open 原尺寸会话，UI 显示"连接断开，正在重连…（第 N 次）"。

## 备份留痕

- `local/backup-polish7/m4/app-kt-SuMon/app/src/main/java/com/susumonitor/api/TerminalClient.kt`（修改前备份）
- `local/backup-polish7/m4/app-kt-SuMon/app/build.gradle.kts`（testOptions 修改前备份）

## 改动内容

### `api/TerminalClient.kt`（重写状态机）

- `TerminalPhase` 新增 `RECONNECTING`；`TerminalSessionState` 新增 `reconnectAttempts`。
- **触发条件**：
  - `wsClient.connectionState` 变为非 CONNECTED 且相位 ∈ {AWAITING_OPEN, OPEN} 且非用户主动 → 进入 RECONNECTING 并调度退避重连；
  - 通道恢复 CONNECTED 且相位 RECONNECTING → 取消退避定时器**立即**重开；
  - `terminal.closed` 帧：`process_exited`（shell 正常退出）**不**重连；其余原因（agent_disconnected 等）→ 自动重连。
- **用户意图**：`close()` / `forceClosed()` 置 `userClosed=true` 并取消重连任务（离开页面不重连）；`open()` / `reset()` 清空意图（手动重试可用）。
- **重连行为**：记住最近 `serverId/cols/rows`；指数退避 1s→30s + [1/2,1] 抖动（对齐 WsClient 策略）；重发前 `ensureConnected` 等待通道 CONNECTED（10s 超时）；`terminal.opened` 成功后 attempts 归零；重发失败则继续退避（attempts++）。
- **Base64 迁移**：`android.util.Base64` → `java.util.Base64`（getEncoder/getDecoder，带 padding 无换行与 Android NO_WRAP 等价、与服务端严格解码器兼容；纯 JVM 可单测——android.util.Base64 在 JVM 单测中返回 null 导致输出解码静默丢弃）。

### `ui/terminal/TerminalViewModel.kt`

- `TerminalUiState` 新增 `reconnectAttempts`，透传映射。

### `ui/terminal/TerminalScreen.kt`

- 相位 `RECONNECTING` 覆盖层提示："连接断开，正在重连…（第 N 次）"（无重试按钮，自动进行）。
- `LaunchedEffect(uiState.phase)`：进入 AWAITING_OPEN（首开/重连/手动重试）时清空终端缓冲（新 PTY 会话）。

### `app/build.gradle.kts`

- `testOptions { unitTests.isReturnDefaultValues = true }`：JVM 单测不加载 Android 框架，Log 等 stub 调用返回默认值（避免 not mocked 异常）。

### 测试（`api/TerminalClientTest.kt` 新增 10 例）

- MockK 注入假 WsClient（messages 用 replay=64 缓冲消除订阅竞态；退避参数调小 10ms/60ms 快速收敛；断言用虚拟时间轮询 + 真实时间兜底）。
- 覆盖：open→opened 状态机 / input+resize 仅 OPEN 发送 / close 阻断重连 / WS 断开进入 RECONNECTING 并恢复重开 / 异常关闭自动重连 / process_exited 不重连 / attempts 增长与归零 / 输出仅 OPEN 投递 / 错误保留会话并展示 / forceClosed 终止重连。

## 调试实录（留痕）

1. **android.util.Base64 单测陷阱**：`isReturnDefaultValues` 使静态方法返回 null，输出解码 getOrNull 后静默丢弃，`output delivered` 用例超时。换 `java.util.Base64` 解决（顺带统一了与服务端解码器的一致性）。
2. **SharedFlow 订阅竞态**：messages 无 replay 时，collect 订阅（IO 线程异步）前的 emit 被丢弃。测试侧用 `replay=64` 消除；`delay(n)` 为虚拟时间不等 IO 线程，断言统一改条件轮询。
3. **verify 计数**：close 测试初始断言 1 帧，实际 open+close 2 帧；input+resize 用例 2 帧→3 帧，均修正。

## 边界与说明

- `agent_disconnected` 时服务端不向 monitor 推送 terminal.closed（仅收口元数据），Android 端在 Agent 掉线期间表现为"无输出"；该场景由 WS 心跳（OkHttp pingInterval）兜底检测，属服务端中继增强方向，不在本模块范围。
- 超时类关闭（idle_timeout / max_session_duration）会触发自动重开（新会话计时重新开始）——对监控终端属可接受语义，文档声明。

## 验证

- `./gradlew :app:testDebugUnitTest`：85 tests 全绿（75 + 10）。
- `./gradlew :app:assembleDebug`：BUILD SUCCESSFUL。

## 下一步

- M5：Agent metrics.nack 有限重试策略（retriable_server_error + snapshot v3）

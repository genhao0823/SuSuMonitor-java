# 开发日志: Android 自研 ANSI 终端模拟器（TerminalEmulator）

**日期**: 2026-08-12
**操作人**: ZCode
**阶段**: Polish-7 M3

## 背景

Android SSH 终端此前为"自绘简化版"：`MutableList<String>` 行缓冲 + 正则剥离 ANSI（`SimpleTerminalView.feedTerminal`），颜色/清行/光标移动等控制序列被直接丢弃，top/htop 类 TUI 无法使用（README 已知遗留：ANSI 光标/滚屏/TUI 仍为简化渲染）。本模块将终端升级为**自研终端模拟器**：增量 ANSI 解析 + 双屏网格缓冲 + 逐格渲染，零第三方依赖，纯 Kotlin JVM 可单测。

## 备份留痕

- `local/backup-polish7/m3/app-kt-SuMon/app/src/main/java/com/susumonitor/ui/terminal/SimpleTerminalView.kt`（旧版渲染，重写前备份）
- `local/backup-polish7/m3/app-kt-SuMon/app/src/test/java/com/susumonitor/ui/terminal/TerminalBufferTest.kt`（旧版测试，重写前备份）

## 改动内容

### 新增 `ui/terminal/TerminalEmulator.kt`（纯 Kotlin，无 Android 依赖）

- `Cell`：字符 + 前景/背景调色板索引 + bold（fg/bg 为 xterm 256 索引，-1 默认）。
- `TerminalPalette`：16 基础色 + 6×6×6 立方体 + 24 级灰阶 → ARGB。
- `Screen`：rows×cols 固定网格，resize 保留左上角内容。
- `TerminalBuffer`：主/备用双屏 + 滚动回退（上限 2000 行）+ 光标（可见性/保存恢复）+ 滚动区 + wrapPending + 样式游标；**增量状态机解析**（TEXT/ESC/CSI/OSC + UTF-8 续字节，支持跨 feed 分片）；`feed`/`visibleRows`/`resize` 加锁（WS 线程写入 vs UI 线程快照）。
  - CSI 子集：`H/f` 定位、`A/B/C/D` 移动、`G` 列、`d` 行、`J/K` 清除（含 3J 清滚动回退）、`m` SGR（0/1/22、30-37/90-97 前景、40-47/100-107 背景、38;5;n/48;5;n 256 色、39/49）、`h/l` 模式（?1049/?47/?25/?7）、`s/u` 保存恢复光标、`r` 滚动区、`S/T` 滚动、`n` 状态报告（应答 `ESC[row;colR`）、`X/@/P` 擦除/插入/删除字符、`L/M` 插入/删除行。
  - 单字符控制：CR/LF（隐式 CR 兼容裸 `\n`）/BS/TAB；ESC 7/8 保存恢复光标、ESC D/M 索引/反向索引；OSC 忽略至 BEL/ST。
  - `reportOutput` 回调：CSI n 应答经 UI 层回传 PTY 输入通道。
- 边界外（文档声明）：vi 全指令集、DEC 私有光标样式、OSC 超链接、真彩色 38;2（忽略）。

### 重写 `ui/terminal/SimpleTerminalView.kt`

- `feedTerminal`（ANSI 剥离）删除；渲染改为逐格：背景色块（按 bg 分组）+ 同样式文本段（按 fg/bold 分组，减少 drawText 次数）+ 块状光标（半透明白）。
- 视口 = 快照（滚动回退 + 屏幕）末尾 visibleCount 行；光标行不在视口内不绘制；行右侧按当前 cols 截断（resize 后旧滚动回退行兼容）。

### 修改 `ui/terminal/TerminalScreen.kt`

- `remember { TerminalBuffer() }` + `bufferVersion: mutableLongStateOf`：输出回调 `buffer.feed(bytes)` 后版本号递增驱动重组；`LaunchedEffect(cols, rows)` 中 `buffer.resize` + 首次 open 前 `buffer.clear()`；`buffer.reportOutput` 接 `viewModel.sendInput`。

### 测试（+30 用例，全绿）

- `TerminalBufferTest` 重写（12 例）：字符写入/换行/CR 覆盖/CRLF/BS/TAB/末列回绕/滚动回退上限/resize 保留/clear/UTF-8。
- `TerminalParserTest` 新增（25 例）：SGR（基础/亮色/256 色/reset/39-49）、光标移动与 clamp、1 基定位、行列命令、K/J 清除、滚动区、插删行、插删字符、擦除字符、备用屏切换与光标恢复、?25 可见性、s/u 与 ESC 7/8、DSR 应答、CSI/UTF-8 跨 feed 分片、OSC 忽略、底部换行滚动、ESC D/M。

## 调试实录（留痕）

1. **CSI 参数解析 bug**：`if (csiParams.isEmpty() || csiParams.last() != -1)` 条件写反，连续数字被拆成多个参数（"31"→[3,1]），且分隔符占位 -1 在 dispatch 时被当作参数 0（SGR 被意外复位）。修复：数字追加条件改为 `== -1`，CSI 终结时 `removeAll { it == -1 }`。
2. **行数组引用别名 bug**：滚动/插删行用 `grid[r] = grid[r+1]` 换引用后，`fillRow(bottom)` 会清空与上一行共享的同一数组（上移一行后 grid[bottom-1] 与 grid[bottom] 指向同一数组）。修复：补空白行改用 `Array(cols) { Cell() }` 全新数组。
3. **裸 `\n` 语义**：PTY onlcr 常规输出 `\r\n`，但部分程序只发 `\n`；为兼容旧实现行为，LF 附带隐式 CR（列归零）。

## 验证

- `./gradlew :app:testDebugUnitTest`：75 tests 全绿（45 旧 − 7 旧缓冲测试 + 37 新终端测试 = 75）。
- `./gradlew :app:assembleDebug`：BUILD SUCCESSFUL。
- 真机效果（top/htop TUI、256 色渲染、备用屏切换）待 Android 云端全链路手测一并验收（外部凭据待办）。

## 下一步

- M4：Android 终端断线自动重连（TerminalClient 状态机 + UI 提示）

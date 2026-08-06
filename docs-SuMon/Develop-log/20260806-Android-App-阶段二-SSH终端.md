# 2026-08-06 Android App 阶段二：SSH 终端（自绘 Canvas）

**状态**：代码完成，45 单测全绿 + assembleDebug 成功；**云端联调 PASS**（smoke 账号，`ls`/`whoami`/`hostname` 回显正常）
**前置基线**：阶段一（Web 功能完整移植，40 单测）

## 一、方案演进与决策

用户要求把 Web 端 SSH 终端（xterm.js）移植到 Android。

**第一版：Termux terminal-view（已弃用）**
1. Maven Central 无 `com.termux` 组（numFound=0），官方 maven.termux.dev 不可达
2. 官方 wiki（Termux-Libraries）确认经 **JitPack** 发布，tag `v0.118.0`
3. 坐标 `com.github.termux.termux-app:terminal-view:v0.118.0`（JitPack），Gradle 加 `maven(url = "https://jitpack.io")` 解析成功
4. **弃用原因**：`TerminalSession` 为 final 类且强制本地 PTY（`JNI.createSubprocess` 空 shellPath 崩溃）；仅靠 `TerminalView` + `Emulator` 驱动无 shell 会话与远程 WS 模式不兼容（Termux 是本地终端栈，无远程回显/流协议抽象）

**最终方案：自绘 Canvas 终端 `SimpleTerminalView`**
- 零第三方依赖；等宽字体行缓冲 + `drawText` 渲染
- 完全复用 `/ws/monitor` WebSocket 通道（与 Web 端 TerminalView 同构：不另开 WS，避免双 ticket + message_id 冲突）
- 软键盘字符/退格/回车 → `terminal.input`；PTY 输出 → Base64 解码 → 行缓冲

## 二、协议层

- `WsModels.kt` 8 种终端帧类型常量 + 5 个 payload 模型（open/opened/input/output/resize/close/closed/error）
- `WsMessageParser` 扩展：terminal.opened/output/closed/error 解析 + sealed 分发
- `WsClient.sendTerminalFrame(type, payload)`：帧外壳（type + UUID message_id + UTC timestamp），仅 CONNECTED 时发送
- `TerminalClient`：状态机（IDLE→AWAITING_OPEN→OPEN→CLOSING→CLOSED）、Base64（android.util.Base64）、input/resize/close、输出回调；独立协程收集 messages flow
- `WsClient.ensureConnected(serverId)`：终端页打开前确保 WS 已连并订阅（重试循环，300ms × 27）

## 三、终端页

- **渲染**：`SimpleTerminalView`（Canvas + 等宽字体，深色背景），`feedTerminal` 字节流 → 行缓冲（UTF-8 解码 + ANSI 剥离）
- **换行语义**（PTY onlcr）：`\r\n` 整体视为换行且**保留行内容**（初版把 `\r` 当清行，导致 `ls` 输出全变空行，已修）；单独 `\n` 换行；单独 `\r` 行首覆盖
- **输入**：BasicTextField 透明输入框作键盘事件源 —— 字符 → UTF-8；退格 → DEL(0x7F)；回车 → `\r`(0x0D)
- **状态机驱动**：`TerminalViewModel.open()` 重试直到 AWAITING_OPEN；output 回调写入行缓冲
- **关键修复**：输出回调必须用**不可变副本替换**列表（直接改 MutableList 内容不触发 Compose 快照重组，屏幕不刷新）
- **入口**：服务器详情"终端"按钮，仅 `isApproved` 用户可见
- **断线语义**：不自动重连（对齐 Web 端），错误覆盖层 + "重连"按钮

## 四、验证

```bash
./gradlew :app:testDebugUnitTest   # 45 tests / 0 failures（+5 终端帧 + 2 行缓冲）
./gradlew :app:assembleDebug       # app-debug.apk
```

**云端联调（模拟器 + smoke 账号，wsl111/DESKTOP-VT1VN6O）**：
- `terminal.open` → `terminal.opened` → 提示符 `root@DESKTOP-VT1VN6O:/#` 显示
- `ls` → 文件列表 6 行回显（bin/init/lib/mysql_image.tar/boot/opt/dev/proc/srv/etc/root/sys/home/media/run/tmp）
- `whoami` / `hostname` → 命令回显 + 结果输出
- 退格删除、回车执行、返回关闭（`terminal.closed reason=remote_close`）均正确

新增测试：`TerminalWsModelsTest`（5：opened/output Base64/closed/error/open 帧结构）+ `TerminalBufferTest`（7：含 `\r\n` 保行、提示符回显保行回归）。

## 五、提交

- `feat(android): phase-2 SSH terminal via Termux terminal-view`（初版，后续切自绘）
- `fix(android): self-drawn terminal - recomposition, DEL/CR input, CRLF line buffering`（自绘方案 + 3 修复）
- `docs(android): phase-2 closure - dev log and README update`（本文档）

## 六、遗留与边界

- TerminalView 尺寸自适应（resize 帧）未接入：当前固定 80×24 初始，未随 view 尺寸动态 resize
- ANSI 仅剥离（颜色/清行/光标移动丢弃），不支持光标定位/滚屏/交互式 TUI（top/htop 显示异常，vi 不可用）
- 无 Ctrl/Alt/功能键组合映射（物理键盘）
- WS 半开连接：nginx 空闲断开后 OkHttp 未及时感知，open 帧静默丢失（重连机制依赖 onFailure；后续可加 WS 心跳/主动 ping）
- 行缓冲上限 2000 行（防内存膨胀），超出丢最旧行

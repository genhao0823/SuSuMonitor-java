# 2026-08-06 Android App 阶段二：SSH 终端（Termux terminal-view）

**状态**：代码完成，38 单测全绿 + assembleDebug 成功；云端联调待 approved 账号
**前置基线**：阶段一（Web 功能完整移植，33 单测）

## 一、背景与依赖

用户要求把 Web 端 SSH 终端（xterm.js）移植到 Android。经确认采用 **Termux terminal-view 库**（用户已确认）。

**依赖获取的曲折过程**（记录备用）：
1. Maven Central 无 `com.termux` 组（numFound=0），官方 maven.termux.dev 不可达
2. 官方 wiki（Termux-Libraries）确认经 **JitPack** 发布，tag 为 `v0.118.0`
3. JitPack 模块坐标最终确认为 **`com.github.termux.termux-app:terminal-view:v0.118.0`**（POM 声明 group=com.github.termux.termux-app）
4. Gradle 加 `maven(url = "https://jitpack.io")` 后解析成功

## 二、协议层

- `WsModels.kt` 新增 8 种终端帧类型常量 + 5 个 payload 模型（open/opened/input/output/resize/close/closed/error）
- `WsMessageParser` 扩展：terminal.opened/output/closed/error 解析 + sealed 分发
- `WsClient.sendTerminalFrame(type, payload)`：帧外壳（type + UUID message_id + UTC timestamp），返回是否发送成功
- `TerminalClient`：状态机（IDLE→AWAITING_OPEN→OPEN→CLOSING→CLOSED）、Base64 编解码（android.util.Base64）、发送 input/resize/close、输出回调；独立协程收集 WsClient messages flow

## 三、终端页

- **架构**：Termux `TerminalView` + 无 shell `TerminalSession`（仅驱动渲染，不启动本地进程）
- **输入拦截**：`TerminalViewClient.onKeyDown`/`onCodePoint` 返回 true 并转发 WS（远程 PTY 回显，避免本地双重回显）
- **输出注入**：`session.getEmulator().append(bytes, size)` + `terminalView.onScreenUpdated()`
- **入口**：服务器详情"终端"按钮，仅 `isApproved` 用户可见
- **权限门禁**：approved 才能进入（与 Web 端一致）
- **断线语义**：不自动重连（对齐 Web 端），手动重连按钮

## 四、验证

```bash
./gradlew :app:testDebugUnitTest   # 38 tests / 0 failures（+5 终端帧解析）
./gradlew :app:assembleDebug       # app-debug.apk ~18.8MB
```

新增测试：`TerminalWsModelsTest`（5 用例：opened/output Base64/closed/error/open 帧结构）。

## 五、提交

- `feat(android): phase-2 SSH terminal via Termux terminal-view`

## 六、遗留

- **云端联调未执行**：终端需 approved 账号 + 目标服务器 Agent 在线（PTY）；云端 admin/approved 凭据未留痕，待提供后验证 `ls`/`top` 输出回显
- TerminalView 尺寸自适应（resize 帧）未完整接入（当前固定 80×24 初始，未随 view 尺寸动态 resize）
- 物理按键方向键/回车已映射，Ctrl/Alt 组合键未完整映射
- 终端页退出时仅发 terminal.close，未在 Agent 侧等待确认 closed

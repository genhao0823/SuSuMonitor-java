# app-kt-SuMon — Android 监控客户端

SuSuMonitor 的 Android 客户端（Kotlin + Jetpack Compose），对接通过 HTTPS/WSS
反代 `/api/` 与 `/ws/monitor` 的后端入口 `https://SERVER_IP_OR_DOMAIN`。

> **注意（2026-08-28 已修复）**：后端地址已从硬编码改为 BuildConfig 注入——`app/build.gradle.kts` 的 `defaultConfig` 通过 `buildConfigField` 生成 `BASE_URL`/`WS_URL`（默认 `https://genhaosan.online` / `wss://genhaosan.online/ws/monitor`），`Constants.kt` 改读 `BuildConfig`。构建时可用 `-PBASE_URL=... -PWS_URL=...` 覆盖以切换本地/私有部署；`WsClient.connectOnce` 连接超时后也会显式取消旧连接，避免悬挂。

## 功能（阶段一：Web 端完整移植）

| 模块 | 说明 |
|---|---|
| 认证 | 登录 / 注册 / 退出登录；JWT 持久化（DataStore）；401 会话失效自动回登录 |
| 仪表盘 | 健康/就绪探针、服务器在线/离线/未知分布、未读告警计数、admin 待审核入口、服务器卡片实时指标 |
| 服务器列表 | 搜索防抖、分页、SSH 测试、删除（admin） |
| 服务器表单 | 新建/编辑（SSH 密码/私钥凭据，编辑空凭据保留原值） |
| 服务器详情 | 投递遥测、SSH 测试、主机指纹确认、Agent Token 生成/轮换/吊销 |
| 实时监控 | 指标卡 + 自绘 Canvas 折线图（CPU/内存/磁盘 + 网络 I/O）+ 时间范围 + 阈值线 |
| 告警记录 | 分页、状态筛选、标记已读、通知渠道展示、WS 推送横幅 |
| 告警规则 | admin CRUD + 启停 switch |
| 用户审核 | admin 列表/搜索/单条/批量通过拒绝 |
| 实时推送 | 前台 Service 常驻 + WS 订阅 + 告警系统通知（Android 13+ 权限请求） |
| 设置 | 用户信息、通知开关、后端地址、关于 |
| SSH 终端 | 自研 ANSI 终端模拟器（TerminalEmulator + SimpleTerminalView：解析/着色/渲染非剥离、双屏、滚动回退），复用 /ws/monitor 通道收发 PTY；支持 resize/功能键/Ctrl、断线自动重连（approved 用户，仅入口在服务器详情） |

**阶段二已含**：SSH 终端（自研 ANSI 终端模拟器，支持 top/htop 类 TUI；复用 /ws/monitor WebSocket 通道，无需本地 PTY）。

## 技术栈

- Kotlin 2.0.21 + Jetpack Compose（Material 3）+ ViewModel + StateFlow
- Retrofit 2.11 + OkHttp 4.12 + kotlinx-serialization（REST）
- Hilt 依赖注入
- 前台 Service（START_STICKY + dataSync）+ NotificationChannel
- DataStore Preferences（会话持久化）
- AGP 8.13 + Gradle 8.13（wrapper）；minSdk 26 / target&compileSdk 36

## 构建

```bash
# 前置：JDK 17+、Android SDK（local.properties 指向 sdk.dir）
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 85 个单元测试
```

Debug APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 运行

1. 在 Android Studio 打开 `app-kt-SuMon/`（自动识别 Gradle 工程）
2. 连接真机或启动模拟器，运行 `app` 配置
3. 登录页输入后端账号（首个用户为 admin，或联系管理员审核）

> `network_security_config` 全局禁止明文 HTTP；生产环境仅使用 HTTPS/WSS，部署时将后端入口配置为实际受控域名。

## 云端联调状态（2026-08-06）

- ✅ 注册 / pending 登录 403 / 用户名冲突 409 / 错误友好提示（模拟器 + 云端）
- ✅ 阶段一全流程（smoke 管理员账号）：服务器 CRUD、SSH 测试（指纹未确认 40901 业务预期）、主机指纹格式校验、告警规则 CRUD、用户审核（单条/批量通过拒绝）、实时监控历史图表（1h/6h/24h/7d）
- ✅ 阶段二 SSH 终端：`ls` / `whoami` / `hostname` PTY 输出回显正常，退格/回车/返回关闭正确
- ✅ 终端复用 /ws/monitor 通道（open→opened→input/output→close→closed），断线自动重连（RECONNECTING 相位 + 指数退避重开 + UI 提示）
- ✅ 联调修复 3 个契约/容错 bug：AdminUserVo camelCase、metrics page_size 上限 100、详情页 metrics 404 容错

## 目录结构

```
app/src/main/java/com/susumonitor/
├── api/            # Retrofit 接口 + WsClient + 拦截器
├── data/           # DTO 模型 + Repository + SessionStore + 帧解析
├── di/             # Hilt 模块（网络/接口绑定）
├── service/        # MonitorForegroundService + NotificationHelper
├── ui/             # Compose 页面（login/dashboard/servers/alerts/navigation/components）
├── util/           # 格式化/错误码/常量
└── MainActivity.kt
```

## 协议对齐

- REST：`docs-SuMon/OpenApi-SuMon/*.json`（5 个文件，31 路径/36 端点操作，auth 模块 camelCase，其余 snake_case）
- WebSocket：`docs-SuMon/Protocol-SuMon/websocket-protocol.md` v1.3
  - 先 `POST /api/ws/monitor-ticket` 取 30s 一次性 ticket → 通过 `wss://SERVER_IP_OR_DOMAIN/ws/monitor?ticket=…` 建立连接
  - `metrics.subscribe` → 收 `metrics.update` / `alert.push` / `server.status.update` / `error`
- 错误码：`server-java-SuMon/src/main/java/com/susumonitor/server/common/ErrorCode.java`（40100/40300/40001/40002…）
